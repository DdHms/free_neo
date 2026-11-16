package dev.freeneo.app;

import android.graphics.Bitmap;
import android.os.SystemClock;
import android.view.TextureView;
import android.widget.TextView;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.objects.ObjectDetection;
import com.google.mlkit.vision.objects.ObjectDetector;
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions;

import java.util.List;

/** Samples the displayed preview on the UI thread. ML Kit runs inference asynchronously. */
final class PreviewTracker implements AutoCloseable {
    static final int WIDTH = 320;
    static final int HEIGHT = 180;
    private static final long SAMPLE_INTERVAL_MS = 125;
    private static final long RESULT_MAX_AGE_MS = 500;

    private final TextureView video;
    private final ObjectOverlay overlay;
    private final TextView status;
    private final ObjectDetector detector = ObjectDetection.getClient(new ObjectDetectorOptions.Builder()
        .setDetectorMode(ObjectDetectorOptions.STREAM_MODE)
        .enableMultipleObjects()
        .build());
    private final Bitmap frame = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888);
    private final Runnable clearOverlay = () -> clear();
    private boolean busy, closed;
    private long nextSample;

    PreviewTracker(TextureView video, ObjectOverlay overlay, TextView status) {
        this.video = video;
        this.overlay = overlay;
        this.status = status;
        status.setText("Starting object tracking…");
    }

    void sample() {
        long now = SystemClock.elapsedRealtime();
        if (closed || busy || now < nextSample) return;
        nextSample = now + SAMPLE_INTERVAL_MS;
        if (video.getBitmap(frame) == null) return;
        busy = true;
        detector.process(InputImage.fromBitmap(frame, 0)).addOnCompleteListener(result -> {
            busy = false;
            if (closed) {
                release();
                return;
            }
            if (!result.isSuccessful()) {
                clear();
                status.setText("Object detector unavailable. Retrying…");
                nextSample = SystemClock.elapsedRealtime() + 2000;
                return;
            }
            status.setText("Object tracking");
            if (SystemClock.elapsedRealtime() - now >= RESULT_MAX_AGE_MS) {
                clear();
                return;
            }
            overlay.show(result.getResult());
            overlay.removeCallbacks(clearOverlay);
            overlay.postDelayed(clearOverlay, RESULT_MAX_AGE_MS - (SystemClock.elapsedRealtime() - now));
        });
    }

    private void clear() { overlay.show(List.of()); }

    private void release() {
        detector.close();
        frame.recycle();
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        overlay.removeCallbacks(clearOverlay);
        overlay.endTrackingSession();
        if (!busy) release();
    }
}
