package dev.freeneo.app;

import java.util.List;

public final class TargetAssociationTest {
    public static void main(String[] args) {
        var tracker = new TargetAssociation();
        var original = new TargetAssociation.Box(1, 20, 20, 60, 80);
        var replacement = new TargetAssociation.Box(2, 23, 22, 62, 81);
        var distractor = new TargetAssociation.Box(3, 24, 22, 63, 81);
        tracker.select(original, 0);
        check(tracker.match(List.of(), 100) == null);
        check(tracker.match(List.of(replacement, distractor), 200) == null);
        check(tracker.match(List.of(replacement), 300) == null);
        check(tracker.match(List.of(replacement), 400) == replacement);
        check(tracker.match(List.of(replacement), 1150) == null);
    }

    private static void check(boolean result) {
        if (!result) throw new AssertionError("Unexpected target association");
    }
}
