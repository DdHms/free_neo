package dev.freeneo.app;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** One read of the command service capabilities for a bound Neo 2 peer. */
final class ServiceCapabilities {
    enum State { WAITING_FOR_PEER, QUERY_PENDING, AWAITING_REPLY, SUPPORTED, UNSUPPORTED, FAILED }

    private static final int REQUIRED = (1 << 2) | (1 << 3) | (1 << 5) | (1 << 6);
    private final int localHash;
    private final byte[] local = new byte[23];
    private byte[] peer;
    private int peerHash, boot, querySequence, peerCommandMode;
    private long seenAt, sentAt;
    private State state = State.WAITING_FOR_PEER;

    ServiceCapabilities(String identity) {
        byte[] serial = identity.substring(0, Math.min(identity.length(), 19)).getBytes(StandardCharsets.US_ASCII);
        localHash = hash(serial);
        local[0] = 4;
        local[1] = 2;
        System.arraycopy(serial, 0, local, 3, serial.length);
    }

    WifiServiceSwitch newSwitch(int sequence) { return new WifiServiceSwitch(local, peer, sequence); }

    byte[] presence(int nonce, int counter, int forwardingSequence) {
        var body = ByteBuffer.allocate(55).order(ByteOrder.LITTLE_ENDIAN);
        body.put((byte) 5).put(local).put(new byte[] {4, 4, 4});
        body.position(33);
        body.put(new byte[] {0, 0, 1, 0, 0, 1}).putInt(counter);
        body.putShort(49, (short) nonce).put(51, (byte) 4);
        byte[] frame = PairingExchange.frame(0xee, 0xe9, 0, 0, 0x51, 0x13, body.array());
        return FlightCommand.forwardFrame(frame, localHash, -1, forwardingSequence);
    }

    byte[] wrapService(byte[] frame, int forwardingSequence) {
        return FlightCommand.forwardFrame(frame, localHash, peerHash, forwardingSequence);
    }

    boolean fromBoundPeer(int source, int destination) {
        return peer != null && source == peerHash && destination == localHash;
    }

    boolean commandRouteReady(long now) {
        return state(now) == State.SUPPORTED && peerCommandMode >= 4 && peerCommandMode <= 7;
    }

    boolean fromBoundBroadcast(int source) { return peer != null && source == peerHash; }

    State state(long now) {
        if (peer != null && now - seenAt >= 2_000
                || state == State.AWAITING_REPLY && now - sentAt >= 2_000) state = State.FAILED;
        return state;
    }

    byte[] nextQuery(int sequence, int forwardingSequence, long now) {
        if (state(now) != State.QUERY_PENDING) return null;
        var body = ByteBuffer.allocate(13).order(ByteOrder.LITTLE_ENDIAN)
            .put((byte) 0x20).put((byte) 1).put((byte) 0).putInt(localHash)
            .putShort((short) 4).putShort((short) 6).putShort((short) 10).array();
        byte[] query = PairingExchange.frame(0xee, 0xe9, sequence, 0x40, 0x51, 0x41, body);
        querySequence = sequence;
        sentAt = now;
        state = State.AWAITING_REPLY;
        return FlightCommand.forwardFrame(query, localHash, -1, forwardingSequence);
    }

    void receive(byte[] frame, int source, int destination, long now) {
        if (state(now) == State.FAILED || frame[4] != (byte) 0xe9 || frame[5] != (byte) 0xee
                || frame[9] != 0x51 || (destination != -1 && destination != localHash)) return;
        int type = frame[8] & 255;
        if (frame[10] == 0x13 && (type == 0 || type == 0x20 || type == 0x40)) {
            if (frame.length != 68 || frame[11] != 5 || frame[12] != 0 || frame[13] != 0
                    || frame[14] != 36 || (frame[62] & 0x40) == 0) return;
            byte[] candidate = Arrays.copyOfRange(frame, 12, 35);
            int end = 3;
            while (end < candidate.length && candidate[end] != 0) end++;
            if (end == 3 || source != hash(Arrays.copyOfRange(candidate, 3, end))) return;
            for (int i = end; i < candidate.length; i++) if (candidate[i] != 0) return;
            int generation = word(frame, 60);
            if (generation == 0 || peer != null && !Arrays.equals(peer, candidate)) return;
            if (peer != null && boot != generation) {
                state = State.FAILED;
                return;
            }
            if (peer == null) {
                peer = candidate;
                peerHash = source;
                boot = generation;
                state = State.QUERY_PENDING;
            }
            peerCommandMode = frame[35] & 255;
            seenAt = now;
        } else if (state == State.AWAITING_REPLY && frame[10] == 0x41 && (type == 0x80 || type == 0xc0)
                && source == peerHash && word(frame, 6) == querySequence) {
            readReply(frame);
        }
    }

    private void readReply(byte[] frame) {
        if (frame.length < 24 || frame[12] != 0x20 || frame[13] != 0 || frame[14] != 1
                || frame[15] != 0 || integer(frame, 16) != peerHash || word(frame, 20) != frame.length - 24) return;
        if (frame[11] != 0) {
            state = State.FAILED;
            return;
        }
        boolean cap6 = false, cap10 = false, supported = false;
        for (int i = 22; i < frame.length - 2; i += 19) {
            if (i + 19 > frame.length - 2 || frame[i + 1] != 17 || frame[i + 2] != 0x20) return;
            if (frame[i] == 6 && !cap6) cap6 = true;
            else if (frame[i] == 10 && !cap10) {
                cap10 = true;
                supported = (integer(frame, i + 3) & REQUIRED) == 0
                    && (integer(frame, i + 11) & REQUIRED) == REQUIRED;
            } else return;
        }
        if (cap6 && cap10) state = supported ? State.SUPPORTED : State.UNSUPPORTED;
    }

    static int hash(byte[] serial) {
        long hash = 0;
        for (byte b : serial) hash = (hash * 256 + (b & 255)) % 0xfffffffbL;
        return (int) (((hash * 256 + '_') % 0xfffffffbL * 256 + '0') % 0xfffffffbL);
    }

    static int integer(byte[] data, int i) { return word(data, i) | word(data, i + 2) << 16; }
    private static int word(byte[] data, int i) { return (data[i] & 255) | (data[i + 1] & 255) << 8; }
}
