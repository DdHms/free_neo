package dev.freeneo.app;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HexFormat;

public final class ManualControlTest {
    public static void main(String[] args) {
        byte[] frame = new ManualControl(500, -500, 100, -100).frame(7);
        long word = ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN).getLong(13);
        check(channel(word, 8) == 1156 && channel(word, 19) == 892);
        check(channel(word, 30) == 1090 && channel(word, 41) == 958);
        check(HexFormat.of().formatHex(ManualControl.cameraPitch(-30, 7), 11, 19)
            .equals("00000000d4fe070a"));

        var pictures = new ArrayList<byte[]>();
        var timestamps = new ArrayList<Long>();
        var stream = new HevcAccessUnits((picture, time) -> {
            pictures.add(picture);
            timestamps.add(time);
        });
        for (int type : new int[] {1, 32, 33, 34, 19, 1, 1}) {
            stream.feed(new byte[] {0, 0}, type == 19 ? 42L : 10L);
            stream.feed(new byte[] {1, (byte) (type * 2), 1, (byte) 128, 1}, type == 19 ? 42L : 10L);
        }
        check(pictures.size() == 1 && pictures.get(0).length == 32 && timestamps.get(0) == 42);
        System.out.println("Manual command and HEVC stream checks passed");
    }

    private static int channel(long word, int shift) { return (int) ((word >>> shift) & 2047); }
    private static void check(boolean result) { if (!result) throw new AssertionError(); }
}
