package ru.itmo.plugins.attendance.service.tracking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import ru.itmo.plugins.attendance.configuration.ReportStorageProperties;
import ru.itmo.plugins.attendance.service.support.MutableClock;

class AttendanceArchiveStoreTest {
    private static final Instant START = Instant.parse("2026-11-27T22:30:00Z");
    private final MutableClock clock = new MutableClock(START);
    @TempDir Path directory;
    private AttendanceReportStore store;

    @AfterEach
    void close() {
        if (store != null) store.close();
    }

    @Test
    void groupsAttemptsAndMatchesOnlyExactParticipants() {
        open(Duration.ofDays(30));
        var first = session(START, START.plusSeconds(60), "anna");
        var second = session(START.plusSeconds(120), null, "bob", "anna");
        store.save("room", first);
        store.save("room", second);
        store.save("someone-else", session(START, START, "annabelle"));

        var archive = archive("anna");
        assertThat(archive.total()).isEqualTo(1);
        assertThat(archive.retentionSeconds()).isEqualTo(Duration.ofDays(30).toSeconds());
        var item = archive.items().getFirst();
        assertThat(item.callId()).isEqualTo("room");
        assertThat(item.activatedAt()).isEqualTo(START);
        assertThat(item.trackingSince()).isEqualTo(START.plusSeconds(1));
        assertThat(item.participantCount()).isEqualTo(2);
        assertThat(item.status()).isEqualTo(CallStatus.TRACKING);
        assertThat(item.stoppedAt()).isNull();
        assertThat(item.expiresAt()).isNull();
        assertThat(item.reportAvailable()).isTrue();
        assertThat(archive("ann").items()).isEmpty();
    }

    @Test
    void replacingSnapshotUpdatesMembershipAndStartupRebuildsIndex() {
        open(Duration.ofDays(30));
        var first = session(START, START.plusSeconds(60), "anna");
        store.save("room", first);
        var updated = new AttendanceReport.Session(first.sessionId(), first.activationId(), first.activatedAt(),
                first.trackingSince(), first.endedAt(), first.stoppedAt(), first.capturedAt(), first.status(), null,
                List.of(new AttendanceReport.Visit("bob", START, START.plusSeconds(60), false, "call_ended")), List.of());
        store.save("room", updated);
        assertThat(archive("anna").items()).isEmpty();
        store.close();
        open(Duration.ofDays(30));
        assertThat(archive("bob").items()).extracting(AttendanceArchive.Item::callId).containsExactly("room");
        assertThat(archive("anna").items()).isEmpty();
    }

    @Test
    void exposesGapAndRetentionSummaryAndFallsBackToActivationBeforeConnection() {
        open(Duration.ofDays(30));
        var pending = new AttendanceReport.Session(UUID.randomUUID(), UUID.randomUUID(), START, null, null, null,
                START, CallStatus.CONNECTING, null,
                List.of(new AttendanceReport.Visit("anna", START, null, true, null)), List.of());
        store.save("pending", pending);
        var base = session(START.minusSeconds(120), START, "anna");
        var withGap = new AttendanceReport.Session(base.sessionId(), base.activationId(), base.activatedAt(),
                base.trackingSince(), base.endedAt(), base.stoppedAt(), base.capturedAt(), base.status(), null,
                base.visits(), List.of(new CallSnapshot.Gap(START.minusSeconds(90), START.minusSeconds(60))));
        store.save("finished", withGap);

        var items = archive("anna").items();
        assertThat(items).extracting(AttendanceArchive.Item::callId).containsExactly("pending", "finished");
        assertThat(items.getFirst().trackingSince()).isNull();
        assertThat(items.getFirst().activatedAt()).isEqualTo(START);
        assertThat(items.getLast().hasGaps()).isTrue();
        assertThat(items.getLast().endedAt()).isEqualTo(START);
        assertThat(items.getLast().stoppedAt()).isEqualTo(START);
        assertThat(items.getLast().expiresAt()).isEqualTo(START.plus(Duration.ofDays(30)));
    }

    @Test
    void expiryRemovesMembershipBeforeCleanupEvenIfAnotherAttemptIsRetained() {
        open(Duration.ofDays(30));
        store.save("room", session(START, START, "anna"));
        clock.advance(Duration.ofDays(1));
        store.save("room", session(clock.instant(), clock.instant(), "bob"));
        clock.advance(Duration.ofDays(29));

        assertThat(archive("anna").items()).isEmpty();
        assertThat(archive("bob").items()).hasSize(1);
        assertThat(store.report("room").orElseThrow().sessions()).hasSize(1);
        store.cleanup();
        assertThat(archive("anna").items()).isEmpty();
        assertThat(archive("bob").items()).hasSize(1);
        clock.advance(Duration.ofDays(1));
        store.cleanup();
        assertThat(archive("bob").items()).isEmpty();
    }

    @Test
    void pagesDeterministicallyAndFiltersInclusiveLocalDates() {
        open(Duration.ZERO);
        store.save("b", session(START, START, "anna"));
        store.save("a", session(START, START, "anna"));
        store.save("older", session(START.minusSeconds(86400), START, "anna"));

        var page = store.archive("anna", 2, 1, null, null, ZoneId.of("UTC"));
        assertThat(page.total()).isEqualTo(3);
        assertThat(page.totalPages()).isEqualTo(3);
        assertThat(page.items()).extracting(AttendanceArchive.Item::callId).containsExactly("b");
        assertThat(page.retentionSeconds()).isZero();
        assertThat(page.items().getFirst().expiresAt()).isNull();
        var filtered = store.archive("anna", 1, 20, LocalDate.parse("2026-11-28"), LocalDate.parse("2026-11-28"),
                ZoneId.of("Europe/Moscow"));
        assertThat(filtered.items()).extracting(AttendanceArchive.Item::callId).containsExactly("a", "b");
        assertThat(store.archive("anna", Integer.MAX_VALUE, 100, null, null, ZoneId.of("UTC")).items()).isEmpty();
    }

    @Test
    void listingDoesNotReadFilesButActualDownloadStillRejectsCorruption() throws Exception {
        open(Duration.ZERO);
        var snapshot = session(START, START, "anna");
        store.save("room", snapshot);
        Files.writeString(directory.resolve(snapshot.sessionId() + ".json"), "corrupt");

        assertThat(archive("anna").items()).hasSize(1);
        assertThatThrownBy(() -> store.report("room")).isInstanceOf(ReportStorageException.class);
    }

    @Test
    void failedWriteMarksOnlyAffectedCallUnavailableAndCanRecover() throws Exception {
        open(Duration.ofDays(30));
        var failed = session(START, START, "anna");
        store.save("broken", failed);
        store.save("healthy", session(START, START, "anna"));
        Path file = directory.resolve(failed.sessionId() + ".json");
        Files.delete(file);
        Files.createDirectory(file);
        Files.writeString(file.resolve("blocker"), "cannot replace directory");
        assertThatThrownBy(() -> store.save("broken", failed)).isInstanceOf(ReportStorageException.class);

        var items = archive("anna").items();
        assertThat(items).filteredOn(item -> item.callId().equals("broken"))
                .extracting(AttendanceArchive.Item::reportAvailable).containsExactly(false);
        assertThat(items).filteredOn(item -> item.callId().equals("healthy"))
                .extracting(AttendanceArchive.Item::reportAvailable).containsExactly(true);
        assertThatThrownBy(() -> store.report("broken")).isInstanceOf(ReportStorageException.class);
        Files.delete(file.resolve("blocker"));
        Files.delete(file);
        store.save("broken", failed);
        assertThat(archive("anna").items()).allMatch(AttendanceArchive.Item::reportAvailable);
    }

    @Test
    void expiredFailedAttemptDoesNotBlockRetainedAttemptsOfSameCall() throws Exception {
        open(Duration.ofDays(30));
        var failed = session(START, START, "anna");
        store.save("room", failed);
        Path file = directory.resolve(failed.sessionId() + ".json");
        Files.delete(file);
        Files.createDirectory(file);
        Files.writeString(file.resolve("blocker"), "cannot replace directory");
        assertThatThrownBy(() -> store.save("room", failed)).isInstanceOf(ReportStorageException.class);
        clock.advance(Duration.ofDays(1));
        store.save("room", session(clock.instant(), clock.instant(), "bob"));
        clock.advance(Duration.ofDays(29));

        assertThat(archive("anna").items()).isEmpty();
        assertThat(archive("bob").items()).allMatch(AttendanceArchive.Item::reportAvailable);
        assertThat(store.report("room").orElseThrow().sessions()).hasSize(1);
    }

    private void open(Duration retention) {
        store = new AttendanceReportStore(new ReportStorageProperties(directory, retention), clock);
    }

    private AttendanceArchive archive(String participant) {
        return store.archive(participant, 1, 20, null, null, ZoneId.of("UTC"));
    }

    private static AttendanceReport.Session session(Instant activated, Instant stopped, String... participants) {
        var visits = java.util.Arrays.stream(participants).map(participant ->
                new AttendanceReport.Visit(participant, activated, stopped, true, stopped == null ? null : "call_ended")).toList();
        return new AttendanceReport.Session(UUID.randomUUID(), UUID.randomUUID(), activated, activated.plusSeconds(1),
                stopped, stopped, stopped == null ? activated : stopped, stopped == null ? CallStatus.TRACKING : CallStatus.ENDED,
                null, visits, List.of());
    }
}
