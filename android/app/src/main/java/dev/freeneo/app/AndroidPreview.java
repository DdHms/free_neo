package dev.freeneo.app;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.view.Surface;

import java.io.IOException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

final class AndroidPreview implements AutoCloseable {
    private record Chunk(byte[] bytes, long receivedNanos) {}
    private final ArrayBlockingQueue<Chunk> input = new ArrayBlockingQueue<>(192);
    private final Surface surface;
    private volatile boolean closed;
    private volatile long renderedNanos;
    private volatile String status = "Waiting for live video";

    AndroidPreview(Surface surface) {
        this.surface = surface;
        new Thread(this::decode, "neo2-preview").start();
    }

    void offer(byte[] bytes, long receivedNanos) {
        if (!closed && !input.offer(new Chunk(bytes, receivedNanos))) fail("Video decoder fell behind");
    }

    boolean fresh() {
        return !closed && surface.isValid() && renderedNanos != 0
            && System.nanoTime() - renderedNanos < 500_000_000L;
    }

    String status() { return fresh() ? "Live video" : status; }

    private void decode() {
        MediaCodec codec = null;
        try {
            MediaCodec decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_HEVC);
            codec = decoder;
            var format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, 1920, 1080);
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 2 * 1024 * 1024);
            decoder.configure(format, surface, null, 0);
            decoder.setOnFrameRenderedListener((source, presentationUs, renderedAt) -> {
                long captureNanos = presentationUs * 1000;
                if (!closed && captureNanos > renderedNanos) renderedNanos = captureNanos;
            }, new android.os.Handler(android.os.Looper.getMainLooper()));
            decoder.start();
            var units = new HevcAccessUnits((bytes, timestamp) -> {
                int slot = decoder.dequeueInputBuffer(20_000);
                if (slot < 0) throw new IllegalStateException("Video decoder input unavailable");
                var buffer = decoder.getInputBuffer(slot);
                if (buffer == null || buffer.remaining() < bytes.length) throw new IllegalStateException("Video input too large");
                buffer.put(bytes);
                decoder.queueInputBuffer(slot, 0, bytes.length, timestamp / 1000, 0);
            });
            var info = new MediaCodec.BufferInfo();
            while (!closed) {
                Chunk chunk = input.poll(10, TimeUnit.MILLISECONDS);
                if (chunk != null) units.feed(chunk.bytes(), chunk.receivedNanos());
                for (int slot; (slot = decoder.dequeueOutputBuffer(info, 0)) >= 0;) {
                    boolean fresh = System.nanoTime() - info.presentationTimeUs * 1000 < 500_000_000L;
                    decoder.releaseOutputBuffer(slot, fresh && info.size > 0);
                }
            }
        } catch (IOException | RuntimeException error) {
            fail("Live preview unavailable: " + error.getMessage());
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        } finally {
            if (codec != null) codec.release();
        }
    }

    private void fail(String message) {
        if (!closed) {
            status = message;
            close();
        }
    }

    @Override public void close() {
        closed = true;
        input.clear();
    }
}
