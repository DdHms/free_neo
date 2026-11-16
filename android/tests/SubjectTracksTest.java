package dev.freeneo.app;

import java.util.List;

public final class SubjectTracksTest {
    public static void main(String[] args) {
        var person = SubjectTracks.Kind.PERSON;
        var bicycle = SubjectTracks.Kind.BICYCLE;
        var car = SubjectTracks.Kind.CAR;
        var motorcycle = SubjectTracks.Kind.MOTORCYCLE;
        check(SubjectTracks.Kind.fromCocoIndex(0) == person);
        check(SubjectTracks.Kind.fromCocoIndex(1) == bicycle);
        check(SubjectTracks.Kind.fromCocoIndex(2) == car);
        check(SubjectTracks.Kind.fromCocoIndex(3) == motorcycle);
        check(SubjectTracks.Kind.fromCocoIndex(15) == null);

        var tracks = new SubjectTracks();
        var original = new SubjectTracks.Detection(person, 10, 10, 60, 70);
        var moved = new SubjectTracks.Detection(person, 14, 10, 64, 70);
        var overlappingCar = new SubjectTracks.Detection(car, 14, 10, 64, 70);
        int id = tracks.update(List.of(original), 100).get(0).box().id();
        var samePerson = tracks.update(List.of(moved, overlappingCar), 225);
        check(samePerson.get(0).box().id() == id);
        check(samePerson.get(1).box().id() != id);
        var ambiguous = tracks.update(List.of(original, moved), 350);
        check(ambiguous.stream().noneMatch(track -> track.box().id() == id));
        check(ambiguous.get(0).box().id() != ambiguous.get(1).box().id());

        var gap = new SubjectTracks();
        int gapId = gap.update(List.of(original), 100).get(0).box().id();
        gap.update(List.of(), 300);
        check(gap.update(List.of(moved), 500).get(0).box().id() == gapId);
        check(gap.update(List.of(moved), 1250).get(0).box().id() != gapId);
        System.out.println("Subject filter and track identity checks passed");
    }

    private static void check(boolean condition) {
        if (!condition) throw new AssertionError("Unexpected subject class or track ID");
    }
}
