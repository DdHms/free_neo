package dev.freeneo.app;

public final class TargetFramingTest {
    public static void main(String[] args) {
        var target = new TargetFraming();
        target.select(7);
        for (int i = 0; i < 3; i++) check(target.update(.08, -.08, 50, false, 0));
        check(target.status().contains("Yaw right") && target.status().contains("Tilt up"));
        check(target.correction(0, false).yaw() == 184 && target.correction(0, false).tilt() > 0);
        check(target.correction(300, false) == null);
        target.setSize(0);
        for (int i = 0; i < 4; i++) target.update(0, 0, 40, false, 0);
        check(target.status().contains("Move closer"));
        check(target.correction(0, true).forward() > 0 && target.correction(0, false).forward() == 0);
        target.update(0, 0, 40, true, 0);
        check(target.status().contains("Size cue paused"));
        check(target.correction(0, true).forward() == 0);
        int revision = target.revision();
        target.missing(100);
        check(target.revision() == revision && target.correction(100, true) == null);
        check(target.status().contains("Cues paused"));
        check(target.update(0, 0, 40, false, 700) && target.revision() == revision);
        target.missing(800);
        check(!target.update(0, 0, 40, false, 1550));
        check(!target.update(0, 0, 50, false, 0) && target.status().contains("lost"));
        target.select(9);
        check(target.update(0, 0, 40, false, 0) && target.status().contains("Locked #9"));
    }

    private static void check(boolean condition) {
        if (!condition) throw new AssertionError("Unexpected framing cue");
    }
}
