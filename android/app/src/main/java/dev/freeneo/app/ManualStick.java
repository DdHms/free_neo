package dev.freeneo.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.View;

import java.util.function.BiConsumer;

final class ManualStick extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final BiConsumer<Integer, Integer> input;
    private int pointer = -1;
    private float x, y;

    ManualStick(Context context, String description, BiConsumer<Integer, Integer> input) {
        super(context);
        setContentDescription(description);
        this.input = input;
    }

    @Override protected void onDraw(Canvas canvas) {
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        float r = radius();
        paint.setColor(isEnabled() ? 0xffdbe5ed : 0xffeeeeee);
        canvas.drawCircle(cx, cy, r, paint);
        paint.setColor(isEnabled() ? 0xff284f67 : 0xff888888);
        canvas.drawCircle(cx + x * r, cy - y * r, r / 4, paint);
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
            input.accept(Math.round(x * 200), Math.round(y * 200));
            invalidate();
        }
        return true;
    }

    boolean held() { return pointer != -1; }
}
