package dev.freeneo.app;

import java.util.List;

final class TargetAssociation {
    record Box(int id, float left, float top, float right, float bottom) {
        double area() { return (right - left) * (bottom - top); }

        double overlap(Box other) {
            double intersection = Math.max(0, Math.min(right, other.right) - Math.max(left, other.left))
                * Math.max(0, Math.min(bottom, other.bottom) - Math.max(top, other.top));
            return intersection / (area() + other.area() - intersection);
        }
    }

    private Box last;
    private long seenAt;
    private Integer candidate;

    void reset() { last = null; candidate = null; }

    void select(Box box, long now) {
        last = box;
        seenAt = now;
        candidate = null;
    }

    Box match(List<Box> boxes, long now) {
        if (last == null || now - seenAt >= 750) {
            reset();
            return null;
        }
        Box best = null;
        double score = .45, runnerUp = 0;
        for (Box box : boxes) {
            double overlap = last.overlap(box);
            if (box.id == last.id && overlap >= .18) {
                select(box, now);
                return box;
            }
            if (overlap > score) {
                runnerUp = best == null ? runnerUp : score;
                best = box;
                score = overlap;
            } else {
                runnerUp = Math.max(runnerUp, overlap);
            }
        }
        if (best == null || score - runnerUp < .15) {
            candidate = null;
            return null;
        }
        if (candidate == null || candidate != best.id) {
            candidate = best.id;
            return null;
        }
        select(best, now);
        return best;
    }
}
