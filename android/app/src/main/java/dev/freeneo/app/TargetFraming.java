package dev.freeneo.app;

final class TargetFraming {
    private Integer id;
    private static final long REACQUIRE_MS = 750;
    private boolean lost;
    private long missingSince = -1;
    private double x, y, size, reference;
    private int samples;
    private long observedAt;
    private int revision;

    record Correction(int yaw, int tilt, int forward) {}

    int revision() { return revision; }
    private boolean sizeReliable;

    void select(int id) {
        revision++;
        this.id = id;
        lost = false;
        missingSince = -1;
        samples = 0;
        reference = 0;
    }

    void reassociate() {
        revision++;
    }

    Integer id() { return id; }

    void lose() {
        revision++;
        lost = id != null;
    }

    void missing(long now) {
        if (id == null || lost) return;
        if (missingSince < 0) {
            missingSince = now;
        }
        if (now - missingSince >= REACQUIRE_MS) lose();
    }

    boolean update(double cx, double cy, double scale, boolean clipped, long now) {
        if (missingSince >= 0) missing(now);
        if (id == null || lost) return false;
        missingSince = -1;
        sizeReliable = !clipped && (samples == 0 || Math.abs(scale / size - 1) <= .25);
        double weight = samples == 0 ? 1 : .35;
        x += weight * (cx - x);
        y += weight * (cy - y);
        size += weight * (scale - size);
        samples++;
        observedAt = now;
        return true;
    }

    void setSize(long now) {
        if (!lost && missingSince < 0 && samples >= 3 && sizeReliable && now - observedAt < 300) reference = size;
    }

    Correction correction(long now, boolean keepSize) {
        if (id == null || lost || missingSince >= 0 || samples < 3 || now - observedAt >= 300) return null;
        int yaw = Math.abs(x) <= .04 ? 0 : (int) Math.round(Math.max(-690, Math.min(690, 2300 * x)));
        int tilt = Math.abs(y) <= .06 ? 0 : y < 0 ? 1 : -1;
        int forward = 0;
        if (keepSize && reference > 0 && sizeReliable && Math.abs(x) <= .10 && Math.abs(y) <= .10) {
            double error = 1 - size / reference;
            if (Math.abs(error) > .08) forward = (int) Math.round(Math.max(-400, Math.min(400, 1437.5 * error)));
        }
        return new Correction(yaw, tilt, forward);
    }

    boolean hasSize() { return reference > 0; }

    String status() {
        if (id == null) return "Tap a detection to lock.";
        if (lost) return "Target #" + id + " lost. Tap a detection to relock.";
        if (missingSince >= 0) return "Target #" + id + " temporarily missing · Cues paused";
        if (samples == 0) return "Waiting for target #" + id;
        String framing = "Locked #" + id + " · " + direction(x, "Yaw left", "Yaw right")
            + " · " + direction(y, "Tilt up", "Tilt down");
        if (reference == 0) return framing + " · Waiting for size reference";
        if (!sizeReliable || Math.abs(x) > .15 || Math.abs(y) > .15) return framing + " · Size cue paused";
        double error = size / reference - 1;
        return framing + " · " + (error < -.08 ? "Move closer" : error > .08 ? "Move farther" : "Size held");
    }

    private static String direction(double error, String negative, String positive) {
        return error < -.04 ? negative : error > .04 ? positive : "Centered";
    }
}
