package dev.freeneo.app;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/** Bounded SWUDP subset of the pinned Neo 2 reference transport. */
final class TelemetryProtocol {
    interface Observer {
        void flight(boolean flying, boolean motorsOn);
        void battery(int percent);
        default void flightEvidence(boolean flying, boolean motorsOn) {}
        default void landingConfirmation(boolean needed) {}
        default void flightReply(byte[] frame) {}
    }

    private static final byte[] SYN_TAIL = {
        (byte) 0xc0, (byte) 0xaf, 0x64, 0, 0x64, 0, (byte) 0xc0, 5, 0x14, 0,
        0, 0x64, 0, 0, 1, (byte) 0x90, 1, (byte) 0xc0, 5, 0x14, 0,
        0, 0x64, 0, 0x14, 0, 0x64, 0, (byte) 0xc0, 5, 0x14, 0, 0, 0x64, 0,
        1, 1, 4, 1, 2
    };
    private final int session;
    private final int anchor;
    private final Observer observer;
    private final Window video, protocol;
    private final byte[] stream = new byte[2496];
    private int used;
    private long lastFragmentNanos;
    private boolean accepted, qos, peerObserved;
    private int reportsSent;
    private ServiceCapabilities capabilities;
    private WifiServiceSwitch service;
    private int serviceForwardingSequence = 2;
    private long now;

    TelemetryProtocol(int session, int anchor, Observer observer) {
        this.session = session;
        this.anchor = anchor;
        this.observer = observer;
        video = new Window(anchor, 100, false);
        protocol = new Window(anchor, 256, true);
    }

    boolean accepted() { return accepted; }

    boolean qos() { return qos; }

    byte[] heartbeat(int sequence) {
        return windowDatagram(PairingExchange.frame(2, 0x28, sequence, 0, 0, 0, new byte[] {0, 2, 1, 0}));
    }

    byte[] presence(int nonce, int counter) {
        return windowDatagram(capabilities.presence(nonce, counter, serviceForwardingSequence++));
    }

    void enableCapabilityRead(String identity) { capabilities = new ServiceCapabilities(identity); }

    byte[] nextCapabilityQuery(int sequence, long now) {
        if (!qos || capabilities == null) return null;
        byte[] query = capabilities.nextQuery(sequence, serviceForwardingSequence, now);
        if (query == null) return null;
        serviceForwardingSequence++;
        return windowDatagram(query);
    }

    boolean beginServiceSwitch(int sequence, long now) {
        if (service != null || capabilities.state(now) != ServiceCapabilities.State.SUPPORTED) return false;
        service = capabilities.newSwitch(sequence);
        return true;
    }

    WifiServiceSwitch.Packet nextServicePacket(long now) {
        return service == null || capabilities.state(now) != ServiceCapabilities.State.SUPPORTED
            ? null : service.next(now);
    }

    byte[] serviceDatagram(WifiServiceSwitch.Packet packet) {
        return windowDatagram(capabilities.wrapService(packet.frame(), serviceForwardingSequence++));
    }

    void servicePacketSent(WifiServiceSwitch.Packet packet, long now) { service.sent(packet, now); }

    WifiServiceSwitch.State serviceState(long now) { return service == null ? null : service.state(now); }

    boolean commandReady(long now) {
        return serviceState(now) == WifiServiceSwitch.State.COMMITTED
            && capabilities.commandRouteReady(now);
    }

    byte[] flightDatagram(byte[] frame, long now) {
        if (!commandReady(now)) throw new IllegalStateException("Flight service unavailable");
        return windowDatagram(capabilities.wrapService(frame, serviceForwardingSequence++));
    }

    ServiceCapabilities.State capabilityState(long now) {
        return capabilities.state(now);
    }

    byte[] open() {
        byte[] tail = SYN_TAIL.clone();
        tail[0] = (byte) anchor;
        tail[1] = (byte) (anchor >>> 8);
        return datagram(0, tail);
    }

    byte[] acknowledge() { return windowDatagram(new byte[0]); }

    byte[] nextIdentityReport(String identity, int port, int sequence) {
        if (!qos || reportsSent >= (peerObserved ? 3 : 1)) return null;
        boolean wifiReport = reportsSent == 2;
        byte[] report = new byte[wifiReport ? 31 : 24];
        report[0] = 1;
        report[1] = (byte) port;
        report[2] = (byte) (port >>> 8);
        if (wifiReport) report[3] = 4;
        byte[] serial = identity.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        System.arraycopy(serial, 0, report, wifiReport ? 7 : 4, Math.min(19, serial.length));
        reportsSent++;
        return windowDatagram(PairingExchange.frame(2, wifiReport ? 0x1b : 7, sequence, 0x40, 0x93, report));
    }

    private byte[] windowDatagram(byte[] payload) {
        var body = buffer(154 + payload.length);
        video.encode(body);
        protocol.encode(body);
        body.putShort((short) anchor).putShort((short) anchor).putInt(0).putShort((short) payload.length).put(payload);
        return datagram(4, Arrays.copyOf(body.array(), body.position()));
    }

    private byte[] datagram(int type, byte[] body) {
        var data = buffer(8 + body.length);
        data.putShort((short) (0x8000 | data.capacity())).putShort((short) session)
            .putShort((short) 0).put((byte) type);
        data.put((byte) xor(data.array())).put(body);
        return data.array();
    }

    void receive(byte[] data) { receive(data, 0); }

    void receive(byte[] data, long now) {
        this.now = now;
        if (data.length < 8 || data.length > 1472 || word(data, 0) != (0x8000 | data.length)
                || word(data, 2) != session || xor(data) != u(data[7])) return;
        int type = u(data[6]);
        if (!accepted) {
            accepted = type == 0 && data.length == 15 && data[8] == 1 && (word(data, 9) & ~15) == 0;
            qos = accepted && (word(data, 9) & 2) != 0;
            return;
        }
        if (type == 1 && data.length >= 34) {
            int slots = word(data, 28);
            if (slots > 20) return;
            int lengthOffset = 32 + (slots + 3) / 4;
            int payloadOffset = lengthOffset + 2;
            if (payloadOffset > data.length || word(data, lengthOffset) != data.length - payloadOffset) return;
            video.observe(data, 8);
            protocol.observe(data, 16);
            // Status aggregates are independent of the ordered channel-3 stream.
            if (payloadOffset < data.length) inspectAggregate(data, payloadOffset);
        } else if ((type == 2 || type == 3) && data.length >= 20) {
            (type == 2 ? video : protocol).receive(data);
        }
    }

    private record Fragment(byte[] payload, long receivedNanos) {}

    private final class Window {
        private final int capacity;
        private final boolean deliver;
        private final Map<Integer, Fragment> waiting = new HashMap<>();
        private int start, end;

        Window(int anchor, int capacity, boolean deliver) {
            start = end = anchor;
            this.capacity = capacity;
            this.deliver = deliver;
        }

        void receive(byte[] packet) {
            int sequence = word(packet, 4) & 0xfff8;
            int advertisedStart = word(packet, 8) & 0xfff8;
            int advertisedEnd = word(packet, 10) & 0xfff8;
            if (!inWindow(sequence, start) || !inWindow(sequence, advertisedStart)
                    || !inWindow(advertisedEnd, advertisedStart)
                    || distance(advertisedEnd, sequence) >= capacity
                    || distance(start, advertisedStart) > capacity
                    || distance(advertisedEnd, start) > capacity) return;
            int flags = word(packet, 16) | word(packet, 18) << 16;
            int length = packet.length - 20;
            if ((flags & (1 << 14)) != 0) {
                int count = (flags >>> 8) & 63;
                int index = (flags >>> 15) & 63;
                int tail = flags >>> 21;
                if (count == 0 || index >= (count == 1 ? 2 : count + 2) || tail > length) return;
                if (index >= count) length = 0;
                else if (index == count - 1) length = tail;
            }
            if (distance(advertisedEnd, start) > distance(end, start)) end = advertisedEnd;
            if (!waiting.containsKey(sequence)) {
                byte[] payload = deliver ? Arrays.copyOfRange(packet, 20, 20 + length) : new byte[0];
                waiting.put(sequence, new Fragment(payload, System.nanoTime()));
            }
            int next = (start + 8) & 0xffff;
            while (waiting.containsKey(next)) {
                Fragment fragment = waiting.remove(next);
                start = next;
                if (deliver && fragment.payload.length != 0) {
                    if (System.nanoTime() - fragment.receivedNanos <= 1_000_000_000L) feed(fragment.payload);
                    else used = 0;
                }
                next = (start + 8) & 0xffff;
            }
        }

        void observe(byte[] packet, int offset) {
            int peerStart = word(packet, offset) & 0xfff8;
            int peerEnd = word(packet, offset + 2) & 0xfff8;
            if (distance(peerEnd, peerStart) <= capacity && distance(start, peerStart) <= capacity
                    && distance(peerEnd, start) <= capacity && distance(peerEnd, start) > distance(end, start)) {
                end = peerEnd;
            }
        }

        boolean inWindow(int sequence, int frontier) {
            int slots = distance(sequence, frontier);
            return slots >= 1 && slots <= capacity;
        }

        void encode(ByteBuffer out) {
            int slots = distance(end, start);
            int first = 0, last = 0;
            for (int slot = 1; slot <= slots; slot++) {
                if (!waiting.containsKey((start + slot * 8) & 0xffff)) {
                    if (first == 0) first = slot;
                    last = slot;
                }
            }
            out.putShort((short) start).putShort((short) end);
            if (first == 0) {
                out.putInt(0);
                return;
            }
            out.putShort((short) (last - first + 1)).putShort((short) first);
            for (int slot = first; slot <= last; slot += 4) {
                int bitmap = 0xff;
                for (int i = 0; i < 4 && slot + i <= last; i++) {
                    if (!waiting.containsKey((start + (slot + i) * 8) & 0xffff)) bitmap &= ~(3 << (2 * i));
                }
                out.put((byte) bitmap);
            }
        }
    }

    private void inspectAggregate(byte[] bytes, int offset) {
        while (offset + 13 <= bytes.length) {
            int length = word(bytes, offset + 1) & 1023;
            if (!header(bytes, offset) || length < 13 || length > bytes.length - offset) {
                offset++;
                continue;
            }
            byte[] frame = Arrays.copyOfRange(bytes, offset, offset + length);
            if (validFrame(frame)) {
                inspect(frame);
                offset += length;
            } else {
                offset++;
            }
        }
    }

    private void feed(byte[] payload) {
        long now = System.nanoTime();
        if (used + payload.length > stream.length || now - lastFragmentNanos > 1_000_000_000L) used = 0;
        lastFragmentNanos = now;
        System.arraycopy(payload, 0, stream, used, payload.length);
        used += payload.length;
        int offset = 0;
        while (used - offset >= 4) {
            int length = word(stream, offset + 1) & 1023;
            if (!header(stream, offset) || length < 13) {
                offset++;
                continue;
            }
            if (used - offset < length) break;
            byte[] frame = Arrays.copyOfRange(stream, offset, offset + length);
            if (validFrame(frame)) {
                inspect(frame);
                offset += length;
            } else {
                offset++;
            }
        }
        used -= offset;
        System.arraycopy(stream, offset, stream, 0, used);
    }

    private void inspect(byte[] frame) {
        inspectFlightReply(frame);

        if (frame[4] == 7 && frame[5] == 2 && frame[9] == 7 && u(frame[10]) == 0x94
                && frame.length >= 44 && (frame[11] & 31) != 0 && frame[14] == 2) {
            peerObserved = true;
        }
        if (!plaintextPush(frame[8])) return;
        boolean forwarded = false, boundBroadcast = false;
        if (u(frame[4]) == 0x27 && u(frame[5]) == 0xee && frame[9] == 0x51 && frame[10] == 1) {
            if (frame.length < 26) return;
            int innerLength = word(frame, 12) & 1023;
            int trailer = 11 + innerLength;
            if (innerLength < 13 || frame.length != trailer + 22 + 2
                    || (frame[trailer + 4] & 15) != 2) return;
            int source = ServiceCapabilities.integer(frame, trailer);
            int destination = ServiceCapabilities.integer(frame, trailer + 9);
            frame = Arrays.copyOfRange(frame, 11, trailer);
            if (!validFrame(frame)) return;
            if (qos && capabilities != null) {
                capabilities.receive(frame, source, destination, now);
                if (service != null && capabilities.state(now) == ServiceCapabilities.State.SUPPORTED
                        && capabilities.fromBoundPeer(source, destination)) service.receive(frame, now);
            }
            if (commandReady(now) && (capabilities.fromBoundPeer(source, destination)
                    || (destination == -1 && capabilities.fromBoundBroadcast(source)))) {
                inspectFlightReply(frame);
            }
            if (destination != -1 || !plaintextPush(frame[8])) return;
            boundBroadcast = capabilities != null && capabilities.fromBoundBroadcast(source);
            forwarded = true;
        }
        if (frame[4] != 3 || frame[9] != 3) return;
        int receiver = u(frame[5]);
        if (receiver != 2 && !(forwarded && (receiver == 14 || receiver == 170))) return;
        int payloadLength = frame.length - 13;
        boolean trusted = commandReady(now) && (!forwarded || boundBroadcast);
        if (trusted && frame[10] == 0x53 && frame[8] == 0 && payloadLength == 4
                && (receiver == 2 || receiver == 14)) {
            observer.landingConfirmation((frame[11] & 0x20) != 0);
        }
        if (frame[10] == 0x43 && payloadLength >= 37 && (receiver == 2 || payloadLength == 85)) {
            int flags = u(frame[11 + 32]);
            observer.flight((flags & 14) == 12, (flags & 8) != 0);
            if (trusted) observer.flightEvidence((flags & 14) == 12, (flags & 8) != 0);
        } else if (frame[10] == 0x55 && payloadLength >= 10) {
            int percent = u(frame[11 + 8]);
            if (percent <= 100) observer.battery(percent);
        }
    }

    private void inspectFlightReply(byte[] frame) {
        if (frame[4] == 3 && frame[5] == 2 && frame[9] == 3 && frame[10] == 0x2a
                && commandReady(now)) observer.flightReply(frame);
    }

    private static boolean validFrame(byte[] frame) {
        return frame.length >= 13 && header(frame, 0) && (word(frame, 1) & 1023) == frame.length
            && word(frame, frame.length - 2) == PairingExchange.crc(frame, frame.length - 2, 0x3692, 0x8408);
    }

    private static boolean header(byte[] data, int i) {
        return u(data[i]) == 0x55 && (u(data[i + 2]) >>> 2) == 1
            && u(data[i + 3]) == PairingExchange.crc(data, i, 3, 0x77, 0x8c);
    }

    private static boolean plaintextPush(byte value) { return value == 0 || value == 0x20 || value == 0x40; }
    private static int distance(int a, int b) { return ((a - b) & 0xffff) >>> 3; }
    private static int word(byte[] data, int i) { return u(data[i]) | u(data[i + 1]) << 8; }
    private static int u(byte value) { return value & 255; }
    private static ByteBuffer buffer(int length) { return ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN); }
    private static int xor(byte[] data) {
        int sum = 0;
        for (int i = 0; i < 7; i++) sum ^= u(data[i]);
        return sum;
    }
}
