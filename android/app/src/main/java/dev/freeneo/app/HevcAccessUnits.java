package dev.freeneo.app;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.function.BiConsumer;

/** Reassembles Annex B NALs and groups slices from the same picture. */
final class HevcAccessUnits {
    private static final int LIMIT = 2 * 1024 * 1024;
    private final BiConsumer<byte[], Long> output;
    private final ByteArrayOutputStream unit = new ByteArrayOutputStream();
    private final ByteArrayOutputStream picture = new ByteArrayOutputStream();
    private final byte[][] parameterSets = new byte[3][];
    private int zeros;
    private boolean started, slice, bootstrapped;
    private long received;

    HevcAccessUnits(BiConsumer<byte[], Long> output) { this.output = output; }

    void feed(byte[] chunk, long timestamp) {
        for (byte value : chunk) {
            if (value == 0) {
                if (++zeros >= LIMIT) throw new IllegalArgumentException("Oversized HEVC prefix");
                continue;
            }
            if (value == 1 && zeros >= 2) {
                if (started) finishNal();
                unit.reset();
                unit.writeBytes(new byte[] {0, 0, 0, 1});
                started = true;
                received = timestamp;
            } else if (started) {
                if (unit.size() + zeros + 1 > LIMIT) throw new IllegalArgumentException("Oversized HEVC NAL");
                for (int i = 0; i < zeros; i++) unit.write(0);
                unit.write(value);
            }
            zeros = 0;
        }
    }

    private long pictureReceived;

    private void finishNal() {
        byte[] nal = unit.toByteArray();
        if (nal.length < 7) return;
        int type = (nal[4] >>> 1) & 63;
        boolean video = type <= 31;
        if (slice && ((!video && type >= 32 && type <= 35) || (video && (nal[6] & 128) != 0))) {
            if (bootstrapped) output.accept(picture.toByteArray(), pictureReceived);
            picture.reset();
            slice = false;
        }
        if (type >= 32 && type <= 34) {
            parameterSets[type - 32] = Arrays.copyOf(nal, nal.length);
            if (!bootstrapped) return;
        }
        if (!bootstrapped) {
            if (type < 16 || type > 23 || Arrays.stream(parameterSets).anyMatch(set -> set == null)) return;
            for (byte[] set : parameterSets) picture.writeBytes(set);
            Arrays.fill(parameterSets, null);
            bootstrapped = true;
        }
        if (type == 39 || type == 40) return;
        if (video && !slice) pictureReceived = received;
        slice |= video;
        if (picture.size() + nal.length > LIMIT) throw new IllegalArgumentException("Oversized HEVC picture");
        picture.writeBytes(nal);
    }
}
