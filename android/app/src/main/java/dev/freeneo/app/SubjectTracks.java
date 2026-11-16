package dev.freeneo.app;

import java.util.ArrayList;
import java.util.List;

/** Preserves a track ID only for a unique, mutual overlap match of the same category. */
final class SubjectTracks {
    private static final float MIN_OVERLAP = .45f;
    private static final long MAX_TRACK_GAP_MS = 750;

    enum Kind {
        PERSON("Person"), BICYCLE("Bicycle"), CAR("Car"), MOTORCYCLE("Motorcycle");

        final String label;
        Kind(String label) { this.label = label; }

        static Kind fromCocoIndex(int index) {
            return switch (index) {
                case 0 -> PERSON;
                case 1 -> BICYCLE;
                case 2 -> CAR;
                case 3 -> MOTORCYCLE;
                default -> null;
            };
        }
    }

    record Detection(Kind kind, float left, float top, float right, float bottom) {
        TargetAssociation.Box box(int id) {
            return new TargetAssociation.Box(id, left, top, right, bottom);
        }
    }

    record Track(Kind kind, TargetAssociation.Box box) {}

    private List<Track> previous = List.of();
    private long previousAt;
    private int nextId = 1;

    List<Track> update(List<Detection> detections, long sampledAt) {
        if (sampledAt - previousAt >= MAX_TRACK_GAP_MS) previous = List.of();
        var tracks = new ArrayList<Track>(detections.size());
        for (var detection : detections) {
            var candidate = detection.box(0);
            Track match = null;
            for (var old : previous) {
                if (old.kind() != detection.kind() || candidate.overlap(old.box()) < MIN_OVERLAP) continue;
                if (match != null) {
                    match = null;
                    break;
                }
                match = old;
            }
            int id = match != null && uniqueDetection(match, detections) ? match.box().id() : nextId++;
            tracks.add(new Track(detection.kind(), detection.box(id)));
        }
        if (!tracks.isEmpty()) {
            previous = tracks;
            previousAt = sampledAt;
        }
        return tracks;
    }

    private static boolean uniqueDetection(Track track, List<Detection> detections) {
        int candidates = 0;
        for (var detection : detections) {
            if (detection.kind() == track.kind() && detection.box(0).overlap(track.box()) >= MIN_OVERLAP
                    && ++candidates > 1) return false;
        }
        return true;
    }
}
