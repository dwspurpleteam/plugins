package ru.itmo.plugins.attendance.service.report;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import ru.itmo.plugins.attendance.service.tracking.AttendanceReport;

/** Human-readable presentation shared by the two export formats. Calculations live in AttendanceSummary. */
record ReportRows(String trackingSince, String generatedAt, String timezone, List<Row> rows, List<String> notes) {
    static final List<String> HEADER = List.of("Участник", "Периоды присутствия", "Общее время");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss");

    record Row(String participant, List<String> periods, String duration) {
        List<String> cells() {
            return List.of(participant, String.join(", ", periods), duration);
        }
    }

    static ReportRows prepare(AttendanceReport report, ZoneId zone) {
        var summary = AttendanceSummary.calculate(report);
        var periods = summary.participants().stream().flatMap(participant -> participant.periods().stream()).toList();
        var instants = periods.stream().flatMap(period -> Stream.of(period.from(), period.to()))
                .filter(java.util.Objects::nonNull).toList();
        Instant reference = summary.trackingSince() == null ? report.generatedAt() : summary.trackingSince();
        boolean includeDates = instants.stream().anyMatch(instant ->
                !instant.atZone(zone).toLocalDate().equals(reference.atZone(zone).toLocalDate()));
        boolean includeOffsets = instants.stream().map(instant -> instant.atZone(zone).getOffset()).distinct().count() > 1;
        String pattern = (includeDates ? "dd.MM.yyyy " : "") + "HH:mm:ss" + (includeOffsets ? " XXX" : "");
        DateTimeFormatter time = DateTimeFormatter.ofPattern(pattern).withZone(zone);
        var labels = ParticipantLabels.forIdentities(summary.participants().stream()
                .map(AttendanceSummary.Participant::identity).toList());
        var rows = new ArrayList<Row>();
        for (var participant : summary.participants()) {
            List<String> periodLabels = participant.periods().stream().map(period -> periodLabel(period, time)).toList();
            String duration = participant.duration() == null ? "неизвестно" : durationOf(participant.duration());
            if (participant.duration() != null && participant.unknownStart()) duration += "*";
            if (participant.duration() != null && participant.approximate()) duration = "≈ " + duration;
            rows.add(new Row(labels.get(participant.identity()), periodLabels, duration));
        }
        rows.sort(Comparator.comparing(Row::participant, String.CASE_INSENSITIVE_ORDER).thenComparing(Row::participant));
        var notes = new ArrayList<String>();
        if (periods.stream().anyMatch(period -> period.startBoundary() || period.endBoundary())) {
            notes.add("* — граница учтённого периода, а не точное время входа или выхода.");
        }
        if (summary.participants().stream().anyMatch(AttendanceSummary.Participant::unknownStart)) {
            notes.add("? — время входа неизвестно; * в итоге — сумма только известных периодов.");
        }
        if (summary.hasGaps()) {
            notes.add("Были перерывы связи: входы и выходы могли не сохраниться; итоги с ≈ приблизительны.");
        }
        return new ReportRows(summary.trackingSince() == null ? "ещё не началось"
                : DATE_TIME.format(summary.trackingSince().atZone(zone)),
                DATE_TIME.format(report.generatedAt().atZone(zone)), zone.getId(), List.copyOf(rows), List.copyOf(notes));
    }

    private static String periodLabel(AttendanceSummary.Period period, DateTimeFormatter time) {
        String from = period.from() == null ? "?" : time.format(period.from()) + (period.startBoundary() ? "*" : "");
        String to = period.ongoing() ? "сейчас" : time.format(period.to()) + (period.endBoundary() ? "*" : "");
        return from + "–" + to;
    }

    private static String durationOf(Duration duration) {
        long seconds = duration.toSeconds();
        var parts = new ArrayList<String>();
        if (seconds / 3600 > 0) parts.add(seconds / 3600 + " ч");
        if (seconds / 60 % 60 > 0) parts.add(seconds / 60 % 60 + " мин");
        if (seconds % 60 > 0 || parts.isEmpty()) parts.add(seconds % 60 + " с");
        return String.join(" ", parts);
    }
}
