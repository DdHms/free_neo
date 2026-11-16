package dev.freeneo.app;

import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.TextureView;
import android.widget.TextView;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Samples the displayed preview on the UI thread and runs inference on one worker. */
final class PreviewTracker implements AutoCloseable {
    static final int WIDTH = 320;
    static final int HEIGHT = 180;
    private static final long SAMPLE_INTERVAL_MS = 125;
    private static final long RESULT_MAX_AGE_MS = 500;

    private final TextureView video;
    private final ObjectOverlay overlay;
    private final TextView status;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final SubjectTracks tracks = new SubjectTracks();
    private final Bitmap frame = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888);
    private final Runnable clearOverlay = this::clear;
    private SubjectDetector detector;
    private boolean busy, closed;
    private long nextSample;

    PreviewTracker(TextureView video, ObjectOverlay overlay, TextView status) {
        this.video = video;
        this.overlay = overlay;
        this.status = status;
        status.setText("Starting object detection…");
    }

    void sample() {
        long now = SystemClock.elapsedRealtime();
        if (closed || busy || now < nextSample) return;
        nextSample = now + SAMPLE_INTERVAL_MS;
        if (video.getBitmap(frame) == null) return;
        busy = true;
        worker.execute(() -> {
            try {
                if (detector == null) detector = new SubjectDetector(video.getContext());
                var result = detector.detect(frame);
                main.post(() -> showResult(result, now));
            } catch (IOException | RuntimeException | LinkageError error) {
                android.util.Log.w("FreeNeo", "Object detection failed", error);
                main.post(() -> showResult(null, now));
            }
        });
    }

    private void showResult(List<SubjectTracks.Detection> result, long sampledAt) {
        if (closed) return;
        busy = false;
        if (result == null) {
            clear();
            status.setText("Object detector unavailable. Retrying…");
            nextSample = SystemClock.elapsedRealtime() + 2000;
            return;
        }
        long remaining = RESULT_MAX_AGE_MS - (SystemClock.elapsedRealtime() - sampledAt);
        if (remaining <= 0) {
            clear();
            return;
        }
        status.setText("Object tracking");
        overlay.show(tracks.update(result, sampledAt), sampledAt);
        overlay.removeCallbacks(clearOverlay);
        overlay.postDelayed(clearOverlay, remaining);
    }

    private void clear() { overlay.show(List.of(), -1); }

    @Override public void close() {
        if (closed) return;
        closed = true;
        overlay.removeCallbacks(clearOverlay);
        overlay.endTrackingSession();
        worker.execute(() -> {
            if (detector != null) detector.close();
            frame.recycle();
        });
        worker.shutdown();
    }
}
