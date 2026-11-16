package dev.freeneo.app;

public final class TargetFramingTest {
    public static void main(String[] args) {
        var target = new TargetFraming();
        target.select(7);
        for (int i = 0; i < 3; i++) check(target.update(.08, -.08, 50, false, 0, 0));
        check(target.correction(0, false).tilt() == 0);
        target.update(.08, -.08, 50, false, 100, 100);
        check(target.correction(100, false).tilt() == 0);
        target.update(.08, -.08, 50, false, 200, 200);
        check(target.status().contains("Yaw right") && target.status().contains("Tilt up"));
        check(target.correction(200, false).yaw() == 184 && target.correction(200, false).tilt() > 0);
        check(target.correction(500, false) == null);
        target.setSize(200);
        for (int i = 0; i < 4; i++) target.update(0, 0, 40, false, 200, 200);
        check(target.status().contains("Move closer"));
        check(target.correction(200, true).forward() > 0 && target.correction(200, false).forward() == 0);
        target.update(0, 0, 40, true, 200, 200);
        check(target.status().contains("Size cue paused"));
        check(target.correction(200, true).forward() == 0);
        int revision = target.revision();
        target.missing(300);
        check(target.revision() == revision && target.correction(300, true) == null);
        check(target.status().contains("Cues paused"));
        check(target.update(0, 0, 40, false, 700, 700) && target.revision() == revision);
        target.missing(800);
        check(!target.update(0, 0, 40, false, 1550, 1550));
        check(!target.update(0, 0, 50, false, 0, 0) && target.status().contains("lost"));
        target.select(9);
        check(target.update(0, 0, 40, false, 0, 0) && target.status().contains("Locked #9"));

        var tilt = new TargetFraming();
        tilt.select(10);
        for (int i = 0; i < 3; i++) tilt.update(0, -.2, 40, false, i * 100, i * 100);
        check(tilt.correction(200, false).tilt() == 1);
        tilt.update(0, .2, 40, false, 300, 300);
        check(tilt.correction(300, false).tilt() == 0);
        tilt.update(0, .2, 40, false, 400, 400);
        check(tilt.correction(400, false).tilt() == 0);
        tilt.update(0, .2, 40, false, 500, 500);
        check(tilt.correction(500, false).tilt() == -1);
        tilt.update(0, 0, 40, false, 600, 600);
        check(tilt.correction(600, false).tilt() == 0);
        tilt.update(0, .2, 40, false, 1000, 1000);
        check(tilt.correction(1000, false).tilt() == 0);
    }

    private static void check(boolean condition) {
        if (!condition) throw new AssertionError("Unexpected framing cue");
    }
}
