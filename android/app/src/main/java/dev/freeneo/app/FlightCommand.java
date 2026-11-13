package dev.freeneo.app;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Optional;

/** Packet codec for a flight session. The owning session admits peers and sends packets. */
record FlightCommand(Action action, int sequence) {
    enum Action {
        TAKEOFF(1), LAND(2), CONFIRM_LANDING(0x1e);
        final int value;
        Action(int value) { this.value = value; }
    }

    record Acknowledgement(int status) {
        boolean accepted() { return status == 0; }
    }

    FlightCommand {
        if (action == null || sequence < 0 || sequence > 0xffff) {
            throw new IllegalArgumentException("Invalid flight command");
        }
    }

    byte[] request() {
        return PairingExchange.frame(2, 3, sequence, 0x40, 3, 0x2a, new byte[] {(byte) action.value});
    }

    static byte[] neutral(int sequence) {
        long word = (1L << 62) | (1024L << 8) | (1024L << 19) | (1024L << 30) | (1024L << 41);
        byte[] payload = ByteBuffer.allocate(18).order(ByteOrder.LITTLE_ENDIAN)
            .put((byte) 1).put((byte) 13).putLong(word).putInt(0x200)
            .put(new byte[] {6, 0x55, 1, 4}).array();
        return PairingExchange.frame(2, 0xa9, sequence, 0, 1, 0x0a, payload);
    }

    byte[] forward(int localHash, int peerHash, int forwardingSequence) {
        return forwardFrame(request(), localHash, peerHash, forwardingSequence);
    }

    static byte[] forwardFrame(byte[] inner, int localHash, int peerHash, int forwardingSequence) {
        byte[] payload = ByteBuffer.allocate(inner.length + 22).order(ByteOrder.LITTLE_ENDIAN)
            .put(inner).putInt(localHash).put((byte) 2).putInt(forwardingSequence)
            .putInt(peerHash).put((byte) 1).put((byte) 0x82).array();
        return PairingExchange.frame(0x3b, 0xe9, forwardingSequence & 0xffff, 0, 0x51, 1, payload);
    }

    /** Match an inner acknowledgement already admitted by the owning session. */
    Optional<Acknowledgement> acknowledge(byte[] reply) {
        if (reply.length < 14 || reply.length > 1023 || reply[0] != 0x55
                || word(reply, 1) != (0x400 | reply.length)
                || (reply[3] & 255) != PairingExchange.crc(reply, 3, 0x77, 0x8c)
                || word(reply, reply.length - 2) != PairingExchange.crc(reply, reply.length - 2, 0x3692, 0x8408)
                || reply[4] != 3 || reply[5] != 2 || word(reply, 6) != sequence
                || ((reply[8] & 255) != 0x80 && (reply[8] & 255) != 0xc0)
                || reply[9] != 3 || reply[10] != 0x2a) {
            return Optional.empty();
        }
        return Optional.of(new Acknowledgement(reply[11] & 255));
    }

    private static int word(byte[] bytes, int offset) {
        return (bytes[offset] & 255) | (bytes[offset + 1] & 255) << 8;
    }
}
