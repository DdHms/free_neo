package dev.freeneo.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;

import java.util.function.BiConsumer;

final class ManualStick extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final BiConsumer<Integer, Integer> input;
    private final String top, bottom, left, right;
    private boolean overVideo;
    private int pointer = -1;
    private float x, y;

    ManualStick(Context context, String description, String top, String bottom,
                String left, String right, BiConsumer<Integer, Integer> input) {
        super(context);
        setContentDescription(description);
        this.input = input;
        this.top = top;
        this.bottom = bottom;
        this.left = left;
        this.right = right;
    }

    @Override protected void onDraw(Canvas canvas) {
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        float r = radius();
        paint.setColor(overVideo ? 0x9911191d : isEnabled() ? 0xffe2ece9 : 0xffe7ebec);
        canvas.drawCircle(cx, cy, r, paint);
        paint.setColor(overVideo ? 0xfff7faf9 : 0xff415355);
        paint.setTextAlign(Paint.Align.CENTER);
        float textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 13,
            getResources().getDisplayMetrics());
        paint.setTextSize(Math.min(r * .16f, textSize));
        canvas.drawText(top, cx, cy - r * .62f, paint);
        canvas.drawText(bottom, cx, cy + r * .74f, paint);
        canvas.drawText(left, cx - r * .61f, cy + paint.getTextSize() / 3, paint);
        canvas.drawText(right, cx + r * .61f, cy + paint.getTextSize() / 3, paint);
        paint.setColor(isEnabled() ? 0xff007b67 : overVideo ? 0x99ffffff : 0xffa2b0b3);
        canvas.drawCircle(cx + x * r, cy - y * r, r / 4, paint);
    }

    void setOverVideo(boolean overVideo) {
        this.overVideo = overVideo;
        invalidate();
    }

    private float radius() { return Math.max(1, Math.min(getWidth(), getHeight()) * .42f); }

    void release() {
        if (pointer == -1) return;
        pointer = -1;
        x = y = 0;
        input.accept(0, 0);
        invalidate();
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (!isEnabled()) return false;
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            getParent().requestDisallowInterceptTouchEvent(true);
            pointer = event.getPointerId(0);
        }
        int i = event.findPointerIndex(pointer);
        if (action == MotionEvent.ACTION_CANCEL || action == MotionEvent.ACTION_UP || i < 0
                || (action == MotionEvent.ACTION_POINTER_UP && i == event.getActionIndex())) {
            release();
            return true;
        }
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE) {
            float dx = (event.getX(i) - getWidth() / 2f) / radius();
            float dy = (getHeight() / 2f - event.getY(i)) / radius();
            float distance = (float) Math.hypot(dx, dy);
            float scale = Math.max(0, Math.min(1, (distance - .12f) / .88f)) / Math.max(distance, .01f);
            x = dx * scale;
            y = dy * scale;
            input.accept(Math.round(x * 1250), Math.round(y * 1250));
            invalidate();
        }
        return true;
    }

    boolean held() { return pointer != -1; }
}
