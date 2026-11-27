package ru.itmo.plugins.attendance.service.report;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.TreeMap;

import ru.itmo.plugins.attendance.service.tracking.AttendanceReport;
import ru.itmo.plugins.attendance.service.tracking.AttendanceReport.Session;
import ru.itmo.plugins.attendance.service.tracking.AttendanceReport.Visit;
import ru.itmo.plugins.attendance.service.tracking.CallStatus;

/** Time calculations independent of locale, file format and participant display names. */
record AttendanceSummary(Instant trackingSince, List<Participant> participants, boolean hasGaps) {
    record Period(Instant from, Instant to, boolean startBoundary, boolean endBoundary, boolean ongoing,
            boolean approximate) { }

    record Participant(String identity, List<Period> periods, Duration duration, boolean unknownStart,
            boolean approximate) { }

    private record Interval(Instant from, Instant to) { }
    private record Observation(Instant until, boolean stopped) { }

    static AttendanceSummary calculate(AttendanceReport report) {
        var grouped = new TreeMap<String, List<Period>>();
        Instant started = report.sessions().stream().map(Session::trackingSince).filter(Objects::nonNull)
                .min(Comparator.naturalOrder()).orElse(null);
        boolean hasGaps = false;
        for (Session session : report.sessions()) {
            Observation observation = observation(session, report.generatedAt());
            hasGaps |= !session.gaps().isEmpty();
            for (Visit visit : session.visits()) {
                grouped.computeIfAbsent(visit.participantId(), ignored -> new ArrayList<>())
                        .add(period(visit, session, observation));
            }
        }
        var participants = new ArrayList<Participant>();
        grouped.forEach((identity, periods) -> {
            periods.sort(Comparator.comparing(Period::from, Comparator.nullsFirst(Comparator.naturalOrder()))
                    .thenComparing(Period::to));
            participants.add(new Participant(identity, List.copyOf(periods), total(periods),
                    periods.stream().anyMatch(period -> period.from() == null),
                    periods.stream().anyMatch(Period::approximate)));
        });
        return new AttendanceSummary(started, List.copyOf(participants), hasGaps);
    }

    private static Observation observation(Session session, Instant generatedAt) {
        Instant until = earlier(generatedAt, session.endedAt());
        until = earlier(until, session.stoppedAt());
        if (session.status() == CallStatus.FAILED) until = earlier(until, session.capturedAt());
        boolean stopped = session.stoppedAt() != null || session.status() == CallStatus.FAILED
                || session.status() == CallStatus.ENDED;
        for (var gap : session.gaps()) {
            if (gap.to() == null && !gap.from().isAfter(until)) {
                until = gap.from();
                stopped = true;
            }
        }
        return new Observation(until, stopped);
    }

    private static Period period(Visit visit, Session session, Observation observation) {
        Instant until = observation.until();
        Instant from = visit.joinedAt();
        boolean startBoundary = visit.presentAtStart();
        if (from != null && from.isAfter(until)) {
            // An event timestamp ahead of our clock is not evidence of future attendance.
            from = until;
            startBoundary = true;
        }
        Instant to = earlier(until, visit.leftAt());
        boolean ongoing = visit.leftAt() == null && !observation.stopped();
        boolean endBoundary = (visit.leftAt() == null && observation.stopped())
                || (visit.leftAt() != null && visit.leftAt().isAfter(until));
        if (from != null && to.isBefore(from)) {
            to = from;
            endBoundary = true;
        }
        boolean approximate = false;
        for (var gap : session.gaps()) {
            // We do not subtract a disconnection as if it were known participant absence.
            // Visits intersecting a closed gap retain their observed bounds, but their total is an estimate.
            if (gap.to() != null && gap.from().isBefore(to) && (from == null || gap.to().isAfter(from))) {
                approximate = true;
                break;
            }
        }
        return new Period(from, to, startBoundary, endBoundary, ongoing, approximate);
    }

    private static Instant earlier(Instant value, Instant other) {
        return other != null && other.isBefore(value) ? other : value;
    }

    /** Union avoids counting one instant twice when tracking attempts overlap. */
    private static Duration total(List<Period> periods) {
        List<Interval> intervals = periods.stream().filter(period -> period.from() != null)
                .map(period -> new Interval(period.from(), period.to())).sorted(Comparator.comparing(Interval::from)).toList();
        if (intervals.isEmpty()) return null;
        Duration duration = Duration.ZERO;
        Instant from = intervals.getFirst().from();
        Instant to = intervals.getFirst().to();
        for (int index = 1; index < intervals.size(); index++) {
            Interval interval = intervals.get(index);
            if (!interval.from().isAfter(to)) {
                if (interval.to().isAfter(to)) to = interval.to();
            } else {
                duration = duration.plus(Duration.between(from, to));
                from = interval.from();
                to = interval.to();
            }
        }
        return duration.plus(Duration.between(from, to));
    }
}
