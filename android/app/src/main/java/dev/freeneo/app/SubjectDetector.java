package dev.freeneo.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;

import org.tensorflow.lite.Interpreter;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.List;

/** Runs the bundled COCO EfficientDet-Lite0 model on a single inference thread. */
final class SubjectDetector implements AutoCloseable {
    private static final int SIDE = 320;
    private static final float MIN_CONFIDENCE = .45f;
    private final Interpreter interpreter;
    private final Bitmap modelImage = Bitmap.createBitmap(SIDE, SIDE, Bitmap.Config.ARGB_8888);
    private final Canvas canvas = new Canvas(modelImage);
    private final int[] pixels = new int[SIDE * SIDE];
    private final ByteBuffer input = ByteBuffer.allocateDirect(SIDE * SIDE * 3).order(ByteOrder.nativeOrder());

    SubjectDetector(Context context) throws IOException {
        try (var asset = context.getAssets().openFd("efficientdet_lite0_detection.tflite");
                var stream = asset.createInputStream()) {
            var model = stream.getChannel().map(FileChannel.MapMode.READ_ONLY,
                asset.getStartOffset(), asset.getLength());
            interpreter = new Interpreter(model, new Interpreter.Options().setNumThreads(2));
        } catch (IOException | RuntimeException | LinkageError error) {
            modelImage.recycle();
            throw error;
        }
    }

    List<SubjectTracks.Detection> detect(Bitmap frame) {
        // The preview sample is 320 × 180. Pad the bottom to preserve its aspect ratio.
        canvas.drawColor(Color.BLACK);
        canvas.drawBitmap(frame, 0, 0, null);
        modelImage.getPixels(pixels, 0, SIDE, 0, 0, SIDE, SIDE);
        input.clear();
        for (int pixel : pixels) {
            input.put((byte) (pixel >> 16)).put((byte) (pixel >> 8)).put((byte) pixel);
        }
        input.rewind();
        interpreter.run(input, null);
        // TFLite_Detection_PostProcess output order for this model.
        FloatBuffer boxes = output(0), classes = output(1), scores = output(2);
        int count = Math.min((int) output(3).get(0),
            Math.min(boxes.remaining() / 4, Math.min(classes.remaining(), scores.remaining())));
        var detections = new ArrayList<SubjectTracks.Detection>();
        for (int i = 0; i < count; i++) {
            float score = scores.get(i);
            if (!Float.isFinite(score) || score < MIN_CONFIDENCE) continue;
            float category = classes.get(i);
            int index = (int) category;
            if (category != index) continue;
            var kind = SubjectTracks.Kind.fromCocoIndex(index);
            if (kind == null) continue;
            int offset = i * 4;
            float top = Math.max(0, boxes.get(offset) * SIDE);
            float left = Math.max(0, boxes.get(offset + 1) * SIDE);
            float bottom = Math.min(frame.getHeight(), boxes.get(offset + 2) * SIDE);
            float right = Math.min(frame.getWidth(), boxes.get(offset + 3) * SIDE);
            if (right > left && bottom > top) {
                detections.add(new SubjectTracks.Detection(kind, left, top, right, bottom));
            }
        }
        return detections;
    }

    private FloatBuffer output(int index) {
        return interpreter.getOutputTensor(index).asReadOnlyBuffer().order(ByteOrder.nativeOrder()).asFloatBuffer();
    }

    @Override public void close() {
        interpreter.close();
        modelImage.recycle();
    }
}
