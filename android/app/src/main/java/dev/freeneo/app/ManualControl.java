package dev.freeneo.app;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

record ManualControl(int right, int forward, int up, int yaw) {
    static final ManualControl NEUTRAL = new ManualControl(0, 0, 0, 0);
    private static final int LIMIT = 200;

    ManualControl {
        right = clamp(right);
        forward = clamp(forward);
        up = clamp(up);
        yaw = clamp(yaw);
    }

    private static int clamp(int value) { return Math.max(-LIMIT, Math.min(LIMIT, value)); }

    byte[] frame(int sequence) {
        long word = (1L << 62) | (scaled(right) << 8) | (scaled(forward) << 19)
            | (scaled(up) << 30) | (scaled(yaw) << 41);
        byte[] body = ByteBuffer.allocate(18).order(ByteOrder.LITTLE_ENDIAN)
            .put((byte) 1).put((byte) 13).putLong(word).putInt(0x200)
            .put(new byte[] {6, 0x55, 1, 4}).array();
        return PairingExchange.frame(2, 0xa9, sequence, 0, 1, 0x0a, body);
    }

    private static long scaled(int value) { return (int) (1024 + value * 0.66); }

    static byte[] cameraPitch(int degrees, int sequence) {
        byte[] body = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(0).putShort((short) (degrees * 10)).put((byte) 7).put((byte) 10).array();
        return PairingExchange.frame(2, 4, sequence, 0x40, 4, 0x14, body);
    }
}
