package dev.freeneo.app;

import android.net.Network;
import android.os.SystemClock;
import android.util.Log;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.PortUnreachableException;
import java.net.SocketTimeoutException;
import java.util.Arrays;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicReference;

/** Owns the DJI session on the Android network acquired for the drone. */
final class TelemetryProbe implements AutoCloseable {
    private final Network network;
    private final InetAddress host;
    private final int port;
    private final String identity;
    enum Mode { READ, CHECK_SERVICE, LIVE }
    private record Intent(FlightCommand.Action action, long queuedAt) {}
    private final Mode mode;
    private final Consumer<LiveFlight.Controls> controls;
    private final AndroidPreview preview;
    private record StickInput(ManualControl axes, long at) {}
    private volatile StickInput stickInput;
    private volatile boolean movementReady;
    private final java.util.concurrent.atomic.AtomicBoolean manualArmed = new java.util.concurrent.atomic.AtomicBoolean();

    boolean movementReady() { return movementReady; }
    private final AtomicReference<Integer> cameraPitch = new AtomicReference<>();
    private final LiveFlight flight = new LiveFlight();
    private final AtomicReference<Intent> intent = new AtomicReference<>();
    private final Consumer<String> status;
    private volatile boolean closed;
    private volatile DatagramSocket socket;
    private long lastFlight = -10_000, lastBattery = -10_000;
    private boolean flying, motorsOn;
    private int battery;

    TelemetryProbe(Network network, InetAddress host, int port, String identity, Mode mode, Consumer<String> status,
                   Consumer<LiveFlight.Controls> controls, AndroidPreview preview) {
        this.network = network;
        this.host = host;
        this.port = port;
        this.identity = identity;
        this.mode = mode;
        this.controls = controls;
        this.preview = preview;
        this.status = status;
    }

    void start() { new Thread(this::run, "neo2-telemetry").start(); }

    void armMovement(boolean armed) {
        manualArmed.set(armed && movementReady);
        if (!armed) sticks(null);
    }

    boolean movementArmed() { return manualArmed.get(); }

    void sticks(ManualControl axes) {
        stickInput = axes == null ? null : new StickInput(axes, SystemClock.elapsedRealtime());
    }

    void camera(int pitch) { cameraPitch.set(pitch); }

    void request(FlightCommand.Action action) {
        if (!closed && mode == Mode.LIVE) {
            intent.compareAndSet(null, new Intent(action, SystemClock.elapsedRealtime()));
        }
    }

    private void run() {
        var random = ThreadLocalRandom.current();
        var protocol = new TelemetryProtocol(random.nextInt(0x1000, 0xffff),
            random.nextInt(0x1000, 0xf000) & 0xfff8, new TelemetryProtocol.Observer() {
                @Override public void flight(boolean inFlight, boolean motors) {
                    flying = inFlight;
                    motorsOn = motors;
                    lastFlight = SystemClock.elapsedRealtime();
                }

                @Override public void flightEvidence(boolean inFlight, boolean motors) {
                    flight.observe(inFlight, motors, SystemClock.elapsedRealtime());
                }

                @Override public void landingConfirmation(boolean needed) {
                    flight.confirmation(needed, SystemClock.elapsedRealtime());
                }

                @Override public void video(byte[] payload, long receivedNanos) {
                    if (preview != null) preview.offer(payload, receivedNanos);
                }

                @Override public void flightReply(byte[] frame) { flight.reply(frame); }

                @Override public void battery(int percent) {
                    battery = percent;
                    lastBattery = SystemClock.elapsedRealtime();
                }
            });
        protocol.enableCapabilityRead(identity);
        String endpoint = host.getHostAddress() + ":" + port;
        String step = "opening UDP socket";
        try (var link = new DatagramSocket()) {
            socket = link;
            if (closed) return;
            step = "binding UDP socket to drone Wi-Fi";
            network.bindSocket(link);
            step = "connecting UDP socket";
            link.connect(host, port);
            link.setSoTimeout(20);
            var packet = new DatagramPacket(new byte[1473], 1473);
            long start = SystemClock.elapsedRealtime();
            long nextOpen = start, nextAck = start, nextStatus = start;
            long nextHeartbeat = start + 800, nextPresence = start;
            boolean wasMoving = false;
            long nextMovement = start;
            int opens = 0, presenceCounter = 0;
            int presenceNonce = random.nextInt(1, 65536);
            int reportSequence = random.nextInt(1, 65533);
            while (!closed && ((mode == Mode.LIVE && protocol.accepted()) || SystemClock.elapsedRealtime() - start < 8_000)) {
                long now = SystemClock.elapsedRealtime();
                if (!protocol.accepted() && opens < 4 && now >= nextOpen) {
                    step = "sending DJI session request";
                    send(link, protocol.open());
                    opens++;
                    nextOpen = now + 500;
                }
                if (protocol.accepted() && now >= nextAck) {
                    step = "sending DJI session data";
                    if (protocol.qos() && now >= nextPresence) {
                        send(link, protocol.presence(presenceNonce, presenceCounter++));
                        nextPresence = now + 500;
                    }
                    if (protocol.qos() && now >= nextHeartbeat) {
                        send(link, protocol.heartbeat(reportSequence++ & 0xffff));
                        nextHeartbeat = now + 500;
                    }
                    byte[] report = protocol.nextIdentityReport(identity, port, reportSequence & 0xffff);
                    if (report == null && presenceCounter >= 3) {
                        report = protocol.nextCapabilityQuery(reportSequence & 0xffff, now);
                    }
                    if (report != null) {
                        send(link, report);
                        reportSequence++;
                    } else {
                        if (mode != Mode.READ && protocol.beginServiceSwitch(reportSequence & 0xffff, now)) reportSequence += 2;
                        var servicePacket = protocol.nextServicePacket(now);
                        if (servicePacket == null) {
                            Intent requested = intent.getAndSet(null);
                            FlightCommand command = requested == null || now - requested.queuedAt() > 250
                                ? null : flight.begin(requested.action(),
                                    (reportSequence + (requested.action() == FlightCommand.Action.LAND ? 1 : 0)) & 0xffff,
                                    protocol.commandReady(now), now);
                            if (command == null) {
                                boolean routeReady = protocol.commandReady(now);
                                boolean manualReady = preview != null && preview.fresh()
                                    && now - lastBattery <= 1000 && battery >= 20
                                    && flight.controls(routeReady, now).land();
                                StickInput stick = stickInput;
                                ManualControl axes = manualReady && manualArmed.get() && stick != null && now - stick.at() < 150
                                    ? stick.axes() : ManualControl.NEUTRAL;
                                boolean moving = !axes.equals(ManualControl.NEUTRAL);
                                Integer pitch = cameraPitch.getAndSet(null);
                                if (!manualReady) armMovement(false);
                                boolean sentMovement = routeReady && (moving ? now >= nextMovement : wasMoving);
                                if (sentMovement) {
                                    nextMovement = now + 50;
                                    send(link, protocol.flightDatagram(axes.frame(reportSequence++ & 0xffff), now));
                                }
                                wasMoving = moving || (wasMoving && !routeReady);
                                if (pitch != null && routeReady && preview != null && preview.fresh()) {
                                    send(link, protocol.flightDatagram(ManualControl.cameraPitch(pitch,
                                        reportSequence++ & 0xffff), now));
                                }
                                if (!sentMovement && pitch == null) send(link, protocol.acknowledge());
                            }
                            else {
                                armMovement(false);
                                wasMoving = false;
                                if (command.action() == FlightCommand.Action.LAND) {
                                    send(link, protocol.flightDatagram(FlightCommand.neutral(reportSequence & 0xffff), now));
                                }
                                reportSequence = (command.sequence() + 1) & 0xffff;
                                send(link, protocol.flightDatagram(command.request(), now));
                            }
                        } else {
                            send(link, protocol.serviceDatagram(servicePacket));
                            protocol.servicePacketSent(servicePacket, SystemClock.elapsedRealtime());
                        }
                    }
                    nextAck = now + 20;
                }
                if (now >= nextStatus) {
                    if (mode == Mode.LIVE) {
                        var available = flight.controls(protocol.commandReady(now), now);
                        movementReady = available.land() && preview != null && preview.fresh()
                            && now - lastBattery <= 1000 && battery >= 20;
                        controls.accept(available);
                    }
                    if (protocol.accepted()) status.accept(summary(now) + "\n" + capabilityStatus(protocol.capabilityState(now))
                        + (mode != Mode.READ ? serviceStatus(protocol.serviceState(now)) : "")
                        + (mode == Mode.LIVE ? "\n" + flight.message() : "") + "\n" + endpoint + (preview == null ? "" : "\n" + preview.status()));
                    nextStatus = now + 250;
                }
                try {
                    step = protocol.accepted() ? "receiving DJI session data" : "waiting for DJI session response";
                    packet.setLength(packet.getData().length);
                    link.receive(packet);
                    protocol.receive(Arrays.copyOf(packet.getData(), packet.getLength()), SystemClock.elapsedRealtime());
                } catch (SocketTimeoutException ignored) {
                    // Keep advancing timers if the aircraft is silent.
                }
            }
            if (!closed) {
                if (!protocol.accepted()) status.accept("No DJI session response from " + endpoint + ".");
                else if (mode == Mode.CHECK_SERVICE) {
                    long now = SystemClock.elapsedRealtime();
                    status.accept("Service check ended at " + endpoint + ".\n" + summary(now) + "\n"
                        + capabilityStatus(protocol.capabilityState(now)) + serviceStatus(protocol.serviceState(now)));
                }
                else if (lastFlight < 0 && lastBattery < 0) status.accept("DJI session accepted. No telemetry received.");
                else status.accept("Telemetry probe ended. Tap Read telemetry to refresh.");
            }
        } catch (IOException | SecurityException | IllegalStateException error) {
            if (!closed) {
                Log.w("FreeNeo", "Failed " + step + " at " + endpoint, error);
                String detail = error instanceof PortUnreachableException
                    ? "UDP port unreachable" : error.getClass().getSimpleName() + ": " + error.getMessage();
                status.accept("Failed " + step + " at " + endpoint + ".\n" + detail
                    + (mode == Mode.LIVE && protocol.accepted()
                        ? "\nCheck the aircraft using independent controls." : ""));
            }
        } finally {
            closed = true;
            movementReady = false;
            if (preview != null) preview.close();
            socket = null;
            if (mode == Mode.LIVE) controls.accept(LiveFlight.Controls.DISABLED);
        }
    }

    private static String serviceStatus(WifiServiceSwitch.State state) {
        if (state == null) return "\nWi-Fi service activation did not start";
        return switch (state) {
            case IDLE, WAITING -> "\nNegotiating Wi-Fi command service";
            case COMMITTED -> "\nBoth sides acknowledged Wi-Fi service activation";
            case FAILED -> "\nWi-Fi service activation failed or timed out";
        };
    }

    private static String capabilityStatus(ServiceCapabilities.State state) {
        return switch (state) {
            case WAITING_FOR_PEER -> "Waiting for Neo 2 service announcement";
            case QUERY_PENDING, AWAITING_REPLY -> "Reading DJI service capabilities";
            case SUPPORTED -> "Wi-Fi command capability advertised";
            case UNSUPPORTED -> "Expected Wi-Fi command capability unavailable";
            case FAILED -> "DJI service capability check expired or failed";
        };
    }

    private String summary(long now) {
        String flight = now - lastFlight <= 1000
            ? "Aircraft reports " + (flying ? "flying" : "not flying") + " · motors " + (motorsOn ? "on" : "off")
            : "Flight and motor state unavailable";
        String power = now - lastBattery <= 3000 ? "Battery " + battery + "%" : "Battery unavailable";
        return flight + "\n" + power;
    }

    private static void send(DatagramSocket link, byte[] data) throws IOException {
        link.send(new DatagramPacket(data, data.length));
    }

    @Override public void close() {
        closed = true;
        DatagramSocket current = socket;
        if (current != null) current.close();
    }
}
