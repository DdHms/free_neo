package dev.freeneo.app;

import android.net.Network;
import android.os.SystemClock;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.util.Arrays;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

/** An eight-second observer bound to the Android network acquired for the drone. */
final class TelemetryProbe implements AutoCloseable {
    private final Network network;
    private final InetAddress host;
    private final int port;
    private final String identity;
    private final boolean checkService;
    private final Consumer<String> status;
    private volatile boolean closed;
    private volatile DatagramSocket socket;
    private long lastFlight = -10_000, lastBattery = -10_000;
    private boolean flying, motorsOn;
    private int battery;

    TelemetryProbe(Network network, InetAddress host, int port, String identity, boolean checkService, Consumer<String> status) {
        this.network = network;
        this.host = host;
        this.port = port;
        this.identity = identity;
        this.checkService = checkService;
        this.status = status;
    }

    void start() { new Thread(this::run, "neo2-telemetry").start(); }

    private void run() {
        var random = ThreadLocalRandom.current();
        var protocol = new TelemetryProtocol(random.nextInt(0x1000, 0xffff),
            random.nextInt(0x1000, 0xf000) & 0xfff8, new TelemetryProtocol.Observer() {
                @Override public void flight(boolean inFlight, boolean motors) {
                    flying = inFlight;
                    motorsOn = motors;
                    lastFlight = SystemClock.elapsedRealtime();
                }

                @Override public void battery(int percent) {
                    battery = percent;
                    lastBattery = SystemClock.elapsedRealtime();
                }
            });
        protocol.enableCapabilityRead(identity);
        try (var link = new DatagramSocket()) {
            socket = link;
            if (closed) return;
            network.bindSocket(link);
            link.connect(host, port);
            link.setSoTimeout(20);
            send(link, protocol.open());
            var packet = new DatagramPacket(new byte[1473], 1473);
            long start = SystemClock.elapsedRealtime();
            long nextAck = start, nextStatus = start;
            int reportSequence = random.nextInt(1, 65533);
            while (!closed && SystemClock.elapsedRealtime() - start < 8_000) {
                long now = SystemClock.elapsedRealtime();
                if (protocol.accepted() && now >= nextAck) {
                    byte[] report = protocol.nextIdentityReport(identity, port, reportSequence);
                    if (report == null) report = protocol.nextCapabilityQuery(reportSequence, now);
                    if (report != null) {
                        send(link, report);
                        reportSequence++;
                    } else {
                        if (checkService && protocol.beginServiceSwitch(reportSequence, now)) reportSequence += 2;
                        var servicePacket = protocol.nextServicePacket(now);
                        if (servicePacket == null) send(link, protocol.acknowledge());
                        else {
                            send(link, protocol.serviceDatagram(servicePacket));
                            protocol.servicePacketSent(servicePacket, SystemClock.elapsedRealtime());
                        }
                    }
                    nextAck = now + 20;
                }
                if (now >= nextStatus) {
                    if (protocol.accepted()) status.accept(summary(now) + "\n" + capabilityStatus(protocol.capabilityState(now))
                        + (checkService ? serviceStatus(protocol.serviceState(now)) : ""));
                    nextStatus = now + 250;
                }
                try {
                    packet.setLength(packet.getData().length);
                    link.receive(packet);
                    protocol.receive(Arrays.copyOf(packet.getData(), packet.getLength()), SystemClock.elapsedRealtime());
                } catch (SocketTimeoutException ignored) {
                    // Keep advancing timers if the aircraft is silent.
                }
            }
            if (!closed) {
                if (!protocol.accepted()) status.accept("No DJI session response. Check the configured address and port.");
                else if (checkService) status.accept("Service check ended." + serviceStatus(
                    protocol.serviceState(SystemClock.elapsedRealtime())));
                else if (lastFlight < 0 && lastBattery < 0) status.accept("DJI session accepted. No telemetry received.");
                else status.accept("Telemetry probe ended. Tap Read telemetry to refresh.");
            }
        } catch (IOException | SecurityException error) {
            if (!closed) status.accept("Telemetry connection failed on the drone’s Wi-Fi.");
        } finally {
            socket = null;
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
