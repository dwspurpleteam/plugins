package ru.itmo.plugins.attendance.service.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.imageio.ImageIO;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

import ru.itmo.plugins.attendance.service.tracking.AttendanceReport;
import ru.itmo.plugins.attendance.service.tracking.AttendanceReport.Session;
import ru.itmo.plugins.attendance.service.tracking.AttendanceReport.Visit;
import ru.itmo.plugins.attendance.service.tracking.CallStatus;
import ru.itmo.plugins.attendance.service.tracking.CallSnapshot.Gap;

class AttendanceReportRendererTest {
    private static final Instant START = Instant.parse("2026-11-27T10:00:00Z");
    private static final ZoneId UTC = ZoneId.of("UTC");
    private final AttendanceReportRenderer renderer = new AttendanceReportRenderer();

    @Test
    void groupsAllPeriodsInOneParticipantRowWithReadableTotal() {
        var report = report(List.of(
                new Visit("Анна", START, START.plusSeconds(10), false, "participant_left"),
                new Visit("Анна", START.plusSeconds(20), null, false, null)), 60);
        var view = ReportRows.prepare(report, ZoneId.of("Europe/Moscow"));
        assertThat(view.rows()).hasSize(1);
        assertThat(view.rows().getFirst().participant()).isEqualTo("Анна");
        assertThat(view.rows().getFirst().periods()).containsExactly("13:00:00–13:00:10", "13:00:20–сейчас");
        assertThat(view.rows().getFirst().duration()).isEqualTo("50 с");
        assertThat(view.notes()).isEmpty();
    }

    @Test
    void csvHasOnlyThreeColumnsAndEscapesPotentialFormulas() {
        var report = report(List.of(
                new Visit("Анна", START, START.plusSeconds(10), false, "participant_left"),
                new Visit("Анна", START.plusSeconds(20), null, false, null),
                new Visit("=HYPERLINK(\"evil\");\nID", null, START.plusSeconds(40), false, "participant_left")), 60);
        String csv = new String(renderer.csv(report, ZoneId.of("Europe/Moscow")), StandardCharsets.UTF_8);
        assertThat(csv).startsWith("\ufeff\"Начало отслеживания\";");
        assertThat(csv).contains("\"Участник\";\"Периоды присутствия\";\"Общее время\"",
                "\"Анна\";\"13:00:00–13:00:10, 13:00:20–сейчас\";\"50 с\"", "Europe/Moscow");
        assertThat(csv).contains("\"'=HYPERLINK(\"\"evil\"\");\nID\"");
        assertThat(csv).doesNotContain("Тип записи", "Активация", "TRACKING", report.callId(),
                report.sessions().getFirst().sessionId().toString());
    }

    @Test
    void interruptedSessionUsesLastObservationWithoutInventingAnExit() {
        var session = new Session(UUID.randomUUID(), UUID.randomUUID(), START, START, null,
                START.plusSeconds(15), START.plusSeconds(15), CallStatus.FAILED, "Перезапуск",
                List.of(new Visit("Анна", START, null, false, null)), List.of());
        var view = ReportRows.prepare(new AttendanceReport("RM_1", START.plusSeconds(600), List.of(session)), UTC);
        assertThat(view.rows().getFirst().periods()).containsExactly("10:00:00–10:00:15*");
        assertThat(view.rows().getFirst().duration()).isEqualTo("15 с");
        assertThat(view.notes()).anySatisfy(note -> assertThat(note).contains("граница учтённого периода"));
    }

    @Test
    void displayedPeriodAndTotalUseTheSameObservationBoundary() {
        var view = ReportRows.prepare(report(List.of(
                new Visit("Анна", START, START.plusSeconds(100), false, "participant_left")), 60), UTC);
        assertThat(view.rows().getFirst().periods()).containsExactly("10:00:00–10:01:00*");
        assertThat(view.rows().getFirst().duration()).isEqualTo("1 мин");
    }

    @Test
    void unknownStartAndSeededPresenceStayExplicit() {
        var view = ReportRows.prepare(report(List.of(
                new Visit("Анна", null, START.plusSeconds(5), false, "participant_left"),
                new Visit("Анна", START.plusSeconds(10), START.plusSeconds(20), false, "participant_left"),
                new Visit("Борис", START, START.plusSeconds(30), true, "participant_left"),
                new Visit("Иван", null, START.plusSeconds(40), false, "participant_left")), 60), UTC);
        assertThat(view.rows().get(0).periods()).containsExactly("?–10:00:05", "10:00:10–10:00:20");
        assertThat(view.rows().get(0).duration()).isEqualTo("10 с*");
        assertThat(view.rows().get(1).periods()).containsExactly("10:00:00*–10:00:30");
        assertThat(view.rows().get(2).duration()).isEqualTo("неизвестно");
    }

    @Test
    void guestNamesMatchUiAndDuplicateNamesRemainDistinct() {
        var view = ReportRows.prepare(report(List.of(
                new Visit("anna-0123456789abcde", START, null, false, null),
                new Visit("anna-abcdef012345678", START, null, false, null),
                new Visit("ivan_petrov-123456789012345", START, null, false, null),
                new Visit("participant-987654321098765", START, null, false, null)), 60), UTC);
        assertThat(view.rows()).extracting(ReportRows.Row::participant)
                .containsExactly("Anna #0123", "Anna #abcd", "Ivan Petrov", "Участник #9876");
    }

    @Test
    void overlappingSessionsDoNotCountPresenceTwice() {
        var first = report(List.of(new Visit("Анна", START, START.plusSeconds(20), false, "participant_left")), 60);
        var second = report(List.of(new Visit("Анна", START.plusSeconds(10), START.plusSeconds(30), false,
                "participant_left")), 60);
        var combined = new AttendanceReport("RM_1", START.plusSeconds(60),
                List.of(first.sessions().getFirst(), second.sessions().getFirst()));
        var row = ReportRows.prepare(combined, UTC).rows().getFirst();
        assertThat(row.periods()).hasSize(2);
        assertThat(row.duration()).isEqualTo("30 с");
    }

    @Test
    void crossMidnightPeriodsIncludeDatesAndTotalsIncludeHours() {
        Instant midnight = Instant.parse("2026-11-27T23:30:00Z");
        var session = new Session(UUID.randomUUID(), UUID.randomUUID(), midnight, midnight, null, null,
                midnight.plusSeconds(5400), CallStatus.TRACKING, null,
                List.of(new Visit("Анна", midnight, midnight.plusSeconds(5400), false, "participant_left")), List.of());
        var row = ReportRows.prepare(new AttendanceReport("RM_1", midnight.plusSeconds(5400), List.of(session)), UTC)
                .rows().getFirst();
        assertThat(row.periods()).containsExactly("27.11.2026 23:30:00–28.11.2026 01:00:00");
        assertThat(row.duration()).isEqualTo("1 ч 30 мин");
    }

    @Test
    void pdfIsACompactTableWithOneEntryPerParticipant() throws Exception {
        var report = report(List.of(
                new Visit("Анна", START, START.plusSeconds(900), false, "participant_left"),
                new Visit("Анна", START.plusSeconds(1200), START.plusSeconds(2400), false, "participant_left"),
                new Visit("Борис", START, START.plusSeconds(3600), false, "participant_left"),
                new Visit("Иван", START.plusSeconds(300), START.plusSeconds(600), false, "participant_left"),
                new Visit("Иван", START.plusSeconds(1800), START.plusSeconds(4500), false, "participant_left")), 4500);
        byte[] pdf = renderer.pdf(report, ZoneId.of("Europe/Moscow"));
        writePreview(pdf, "attendance-sample");
        try (var document = Loader.loadPDF(pdf)) {
            String text = new PDFTextStripper().getText(document);
            assertThat(document.getNumberOfPages()).isEqualTo(1);
            assertThat(text).contains("Посещаемость", "Участник", "Периоды присутствия", "Общее время",
                    "13:00:00–13:15:00", "13:20:00–13:40:00", "35 мин", "1 ч", "50 мин");
            assertThat(text.split("Анна", -1)).hasSize(2);
            assertThat(text).doesNotContain("Активация", "Сеанс", "TRACKING", report.callId(),
                    report.sessions().getFirst().sessionId().toString());
        }
    }

    @Test
    void pdfEmbedsCyrillicFontWrapsLongIdsAndRepeatsTableHeaders() throws Exception {
        var visits = new ArrayList<Visit>();
        for (int i = 0; i < 160; i++) {
            visits.add(new Visit("Участник_" + i, START, START.plusSeconds(10), false, "participant_left"));
        }
        visits.add(new Visit("длинный_" + "id".repeat(220) + "😀", START, null, false, null));
        byte[] pdf = renderer.pdf(report(visits, 60), ZoneId.of("Europe/Moscow"));
        writePreview(pdf, "attendance-pagination");
        try (var document = Loader.loadPDF(pdf)) {
            String text = new PDFTextStripper().getText(document);
            assertThat(document.getNumberOfPages()).isGreaterThan(2);
            assertThat(text).contains("Участник_0", "Участник_159");
            for (int i = 0; i < document.getNumberOfPages(); i++) {
                var stripper = new PDFTextStripper();
                stripper.setStartPage(i + 1);
                stripper.setEndPage(i + 1);
                assertThat(stripper.getText(document)).contains("Периоды присутствия", "Общее время");
            }
            for (var name : document.getPage(0).getResources().getFontNames()) {
                assertThat(document.getPage(0).getResources().getFont(name).isEmbedded()).isTrue();
            }
        }
    }

    @Test
    void participantWithManyPeriodsContinuesAcrossPagesWithoutLosingPeriods() throws Exception {
        var visits = new ArrayList<Visit>();
        for (int i = 0; i < 100; i++) {
            visits.add(new Visit("Анна", START.plusSeconds(i * 20), START.plusSeconds(i * 20 + 10),
                    false, "participant_left"));
        }
        try (var document = Loader.loadPDF(renderer.pdf(report(visits, 2000), UTC))) {
            String text = new PDFTextStripper().getText(document);
            assertThat(document.getNumberOfPages()).isGreaterThan(1);
            assertThat(text).contains("10:00:00–10:00:10", "10:33:00–10:33:10", "16 мин 40 с");
            var lastPage = new PDFTextStripper();
            lastPage.setStartPage(document.getNumberOfPages());
            assertThat(lastPage.getText(document)).contains("Анна", "16 мин 40 с");
        }
    }

    @Test
    void ongoingDisconnectionStopsElapsedTimeAtLastObservedPresence() {
        var session = new Session(UUID.randomUUID(), UUID.randomUUID(), START, START, null, null,
                START.plusSeconds(60), CallStatus.RECONNECTING, null,
                List.of(new Visit("Анна", START, null, false, null)),
                List.of(new Gap(START.plusSeconds(15), null)));
        var view = ReportRows.prepare(new AttendanceReport("RM_1", START.plusSeconds(600), List.of(session)), UTC);
        assertThat(view.rows().getFirst().periods()).containsExactly("10:00:00–10:00:15*");
        assertThat(view.rows().getFirst().duration()).isEqualTo("15 с");
    }

    @Test
    void closedDisconnectionMarksOnlyAffectedTotalsAsApproximate() {
        var session = new Session(UUID.randomUUID(), UUID.randomUUID(), START, START, null, null,
                START.plusSeconds(60), CallStatus.TRACKING, null,
                List.of(new Visit("Анна", START, START.plusSeconds(60), false, "participant_left"),
                        new Visit("Борис", START.plusSeconds(45), START.plusSeconds(60), false, "participant_left")),
                List.of(new Gap(START.plusSeconds(15), START.plusSeconds(30))));
        var view = ReportRows.prepare(new AttendanceReport("RM_1", START.plusSeconds(60), List.of(session)), UTC);
        assertThat(view.rows().get(0).duration()).isEqualTo("≈ 1 мин");
        assertThat(view.rows().get(1).duration()).isEqualTo("15 с");
        assertThat(view.notes()).anySatisfy(note -> assertThat(note).contains("приблизительны"));
    }

    @Test
    void futureEventTimestampDoesNotProduceFutureAttendance() {
        var view = ReportRows.prepare(report(List.of(
                new Visit("Анна", START.plusSeconds(90), null, false, null)), 60), UTC);
        assertThat(view.rows().getFirst().periods()).containsExactly("10:01:00*–сейчас");
        assertThat(view.rows().getFirst().duration()).isEqualTo("0 с");
    }

    @Test
    void shortGuestTagsGrowWhenTheyWouldIdentifyDifferentPeopleTheSameWay() {
        var view = ReportRows.prepare(report(List.of(
                new Visit("anna-0123aaaaaaaaaaa", START, null, false, null),
                new Visit("anna-0123bbbbbbbbbbb", START, null, false, null)), 60), UTC);
        assertThat(view.rows()).extracting(ReportRows.Row::participant).containsExactly("Anna #0123a", "Anna #0123b");
    }

    @Test
    void daylightSavingOverlapIncludesOffsetsInsteadOfShowingIdenticalClockTimes() {
        Instant from = Instant.parse("2026-10-25T00:30:00Z");
        Instant to = from.plusSeconds(3600);
        var session = new Session(UUID.randomUUID(), UUID.randomUUID(), from, from, null, null,
                to, CallStatus.TRACKING, null,
                List.of(new Visit("Анна", from, to, false, "participant_left")), List.of());
        var row = ReportRows.prepare(new AttendanceReport("RM_1", to, List.of(session)), ZoneId.of("Europe/Berlin"))
                .rows().getFirst();
        assertThat(row.periods()).containsExactly("02:30:00 +02:00–02:30:00 +01:00");
        assertThat(row.duration()).isEqualTo("1 ч");
    }

    @Test
    void spreadsheetFormulaAfterInvisiblePrefixIsStillEscaped() {
        String identity = " \u0001\ufeff\u200b\u00a0=1+1";
        String csv = new String(renderer.csv(report(List.of(new Visit(identity, START, null, false, null)), 60), UTC),
                StandardCharsets.UTF_8);
        assertThat(csv).contains("\"'" + identity + "\"");
    }

    private static void writePreview(byte[] pdf, String name) throws Exception {
        Files.createDirectories(Path.of("build/reports"));
        Files.write(Path.of("build/reports/" + name + ".pdf"), pdf);
        try (var document = Loader.loadPDF(pdf)) {
            var preview = new PDFRenderer(document);
            ImageIO.write(preview.renderImageWithDPI(0, 110), "png", Path.of("build/reports/" + name + ".png").toFile());
            ImageIO.write(preview.renderImageWithDPI(document.getNumberOfPages() - 1, 110), "png",
                    Path.of("build/reports/" + name + "-last.png").toFile());
        }
    }

    private static AttendanceReport report(List<Visit> visits, long elapsedSeconds) {
        var session = new Session(UUID.randomUUID(), UUID.randomUUID(), START.minusSeconds(1), START,
                null, null, START.plusSeconds(elapsedSeconds), CallStatus.TRACKING, null, visits, List.of());
        return new AttendanceReport("RM_1", START.plusSeconds(elapsedSeconds), List.of(session));
    }
}
