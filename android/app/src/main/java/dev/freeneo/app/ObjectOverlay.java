package dev.freeneo.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.widget.TextView;

import java.util.List;

final class ObjectOverlay extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private List<SubjectTracks.Track> objects = List.of();

    private final TargetFraming framing = new TargetFraming();
    private final TargetAssociation association = new TargetAssociation();
    private TargetAssociation.Box matched;
    private boolean matchVisible;
    private long sampledAt = -1;
    private TextView framingStatus;

    private SubjectTracks.Kind selectedKind;

    TargetFraming framing() { return framing; }
    long sampledAt() { return sampledAt; }

    void setStatusView(TextView status) {
        framingStatus = status;
        updateStatus();
    }

    private Runnable onTargetSelected = () -> {};

    void setOnTargetSelected(Runnable listener) {
        onTargetSelected = listener;
    }

    private void updateStatus() {
        if (framingStatus != null) framingStatus.setText(framing.status());
    }

    ObjectOverlay(Context context) {
        super(context);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    void show(List<SubjectTracks.Track> objects, long sampledAt) {
        this.objects = objects;
        this.sampledAt = sampledAt;
        long now = SystemClock.elapsedRealtime();
        var candidates = objects.stream().filter(object -> object.kind() == selectedKind)
            .map(SubjectTracks.Track::box).collect(java.util.stream.Collectors.toList());
        var selected = association.match(candidates, now);
        matchVisible = selected != null;
        if (selected != null) {
            if (matched != null && matched.id() != selected.id()) framing.reassociate();
            matched = selected;
            framing.update((selected.left() + selected.right()) / (2 * PreviewTracker.WIDTH) - .5,
                (selected.top() + selected.bottom()) / (2 * PreviewTracker.HEIGHT) - .5,
                Math.sqrt(selected.area()),
                selected.left() <= 0 || selected.top() <= 0 || selected.right() >= PreviewTracker.WIDTH
                    || selected.bottom() >= PreviewTracker.HEIGHT, sampledAt, now);
        } else framing.missing(now);
        updateStatus();
        invalidate();
    }

    void endTrackingSession() {
        association.reset();
        matched = null;
        selectedKind = null;
        framing.lose();
        show(List.of(), -1);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) return true;
        if (event.getActionMasked() == MotionEvent.ACTION_UP) {
            int x = (int) (event.getX() * PreviewTracker.WIDTH / getWidth());
            int y = (int) (event.getY() * PreviewTracker.HEIGHT / getHeight());
            for (var object : objects) {
                var box = object.box();
                if (x >= box.left() && x < box.right() && y >= box.top() && y < box.bottom()) {
                    framing.select(box.id());
                    selectedKind = object.kind();
                    matched = box;
                    association.select(matched, SystemClock.elapsedRealtime());
                    show(objects, sampledAt);
                    onTargetSelected.run();
                    performClick();
                    break;
                }
            }
        }
        return true;
    }

    @Override public boolean performClick() { return super.performClick(); }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float sx = getWidth() / (float) PreviewTracker.WIDTH;
        float sy = getHeight() / (float) PreviewTracker.HEIGHT;
        float density = getResources().getDisplayMetrics().density;
        paint.setTextSize(14 * density);
        paint.setStrokeWidth(2 * density);
        for (var object : objects) {
            var box = object.box();
            int id = box.id();
            float x = box.left() * sx, y = box.top() * sy;
            boolean selected = matchVisible && matched != null && id == matched.id();
            paint.setColor(selected ? 0xffffcf62 : 0xff79ffca);
            paint.setStyle(Paint.Style.STROKE);
            canvas.drawRect(x, y, box.right() * sx, box.bottom() * sy, paint);
            paint.setStyle(Paint.Style.FILL);
            String label = object.kind().label + " #" + (selected ? framing.id() : id);
            float baseline = Math.max(18 * density, y);
            canvas.drawRect(x, baseline - 18 * density, x + paint.measureText(label) + 8 * density,
                baseline, paint);
            paint.setColor(0xff10251e);
            canvas.drawText(label, x + 4 * density, baseline - 3 * density, paint);
        }
    }
}
