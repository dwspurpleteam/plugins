package ru.itmo.plugins.attendance.service.tracking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static ru.itmo.plugins.attendance.service.events.CallEventType.CALL_ENDED;
import static ru.itmo.plugins.attendance.service.events.CallEventType.PARTICIPANT_JOINED;
import static ru.itmo.plugins.attendance.service.events.CallEventType.PARTICIPANT_LEFT;
import static ru.itmo.plugins.attendance.service.support.TestActivations.start;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;

import ru.itmo.plugins.attendance.configuration.ReportStorageProperties;
import ru.itmo.plugins.attendance.configuration.TrackingProperties;
import ru.itmo.plugins.attendance.service.events.CallEvent;
import ru.itmo.plugins.attendance.service.support.MutableClock;
import ru.itmo.plugins.attendance.service.support.RecordingEventSource;

class AttendanceReportStoreTest {
    private static final Instant START = Instant.parse("2026-11-27T10:00:00Z");
    private final MutableClock clock = new MutableClock(START);
    private final List<AttendanceReportStore> stores = new ArrayList<>();
    @TempDir Path directory;

    @AfterEach
    void releaseStorageLocks() {
        stores.forEach(AttendanceReportStore::close);
    }

    @Test
    void savesCompleteHistoryAndRecoversInterruptedSessionWithoutExtendingItsDuration() {
        var store = store(Duration.ofDays(30));
        UUID activationId = UUID.randomUUID();
        var tracker = new CallTracker("RM_history", "test", clock, List.of(), List.of("anna"), activationId,
                session -> store.save("RM_history", session));
        clock.advance(Duration.ofSeconds(2));
        tracker.onConnected();
        clock.advance(Duration.ofSeconds(8));
        tracker.onEvent(event("leave", PARTICIPANT_LEFT, "anna"));
        clock.advance(Duration.ofSeconds(10));
        tracker.onEvent(event("join", PARTICIPANT_JOINED, "anna"));
        clock.advance(Duration.ofSeconds(10));
        tracker.checkpoint();
        var before = store.report("RM_history").orElseThrow().sessions().getFirst();
        store.close();

        clock.advance(Duration.ofDays(1));
        var recovered = store(Duration.ofDays(30)).report("RM_history").orElseThrow().sessions().getFirst();

        assertThat(recovered.sessionId()).isEqualTo(before.sessionId());
        assertThat(recovered.activationId()).isEqualTo(activationId);
        assertThat(recovered.activatedAt()).isEqualTo(START);
        assertThat(recovered.trackingSince()).isEqualTo(START.plusSeconds(2));
        assertThat(recovered.stoppedAt()).isEqualTo(START.plusSeconds(30));
        assertThat(recovered.status()).isEqualTo(CallStatus.FAILED);
        assertThat(recovered.statusMessage()).contains("перезапуском");
        assertThat(recovered.visits()).containsExactly(
                new AttendanceReport.Visit("anna", START.plusSeconds(2), START.plusSeconds(10), true, "participant_left"),
                new AttendanceReport.Visit("anna", START.plusSeconds(20), null, false, null));
    }

    @Test
    void archivedReportSurvivesMemoryEvictionAndRegistryShutdown() {
        var store = store(Duration.ofDays(30));
        var registry = registry(store);
        var tracker = start(registry, "RM_history");
        tracker.onConnected();
        tracker.onEvent(event("join", PARTICIPANT_JOINED, "anna"));
        clock.advance(Duration.ofMinutes(5));
        tracker.onEvent(event("end", CALL_ENDED, ""));
        clock.advance(Duration.ofMinutes(2));
        registry.evictStale();
        registry.destroy();
        store.close();

        assertThat(registry.find("RM_history")).isEmpty();
        var archived = store(Duration.ofDays(30)).report("RM_history").orElseThrow();
        assertThat(archived.sessions()).hasSize(1);
        assertThat(archived.sessions().getFirst().status()).isEqualTo(CallStatus.ENDED);
        assertThat(archived.sessions().getFirst().visits()).containsExactly(
                new AttendanceReport.Visit("anna", START, START.plusSeconds(300), false, "call_ended"));
    }

    @Test
    void retryPreservesPreviousTrackingAttemptOfSameActivation() {
        var store = store(Duration.ofDays(30));
        var registry = registry(store);
        var first = start(registry, "RM_history");
        first.onConnected();
        first.onEvent(event("join", PARTICIPANT_JOINED, "anna"));
        clock.advance(Duration.ofMinutes(1));
        first.onFailed("source failed");
        var second = start(registry, "RM_history");
        second.onConnected();

        var report = store.report("RM_history").orElseThrow();
        assertThat(report.sessions()).hasSize(2);
        assertThat(report.sessions()).extracting(AttendanceReport.Session::sessionId).doesNotHaveDuplicates();
        assertThat(report.sessions()).extracting(AttendanceReport.Session::activationId).containsOnly(
                first.reportSession().activationId());
        assertThat(report.sessions().getFirst().visits()).hasSize(1);
        assertThat(report.sessions().getFirst().stoppedAt()).isEqualTo(START.plusSeconds(60));
    }

    @Test
    void expirationDeletesOnlyFinishedReportsAndZeroRetentionKeepsThemForever() throws Exception {
        var store = store(Duration.ofDays(30));
        var tracker = tracker(store);
        tracker.onConnected();
        tracker.onEvent(event("end", CALL_ENDED, ""));
        clock.advance(Duration.ofDays(31));
        assertThat(store.report("RM_history")).isEmpty();
        store.cleanup();
        try (var files = Files.list(directory)) {
            assertThat(files.filter(path -> path.toString().endsWith(".json")).toList()).isEmpty();
        }

        store.close();
        var permanent = store(Duration.ZERO);
        var another = tracker(permanent);
        another.onConnected();
        another.onEvent(event("end2", CALL_ENDED, ""));
        clock.advance(Duration.ofDays(365));
        permanent.cleanup();
        assertThat(permanent.report("RM_history")).isPresent();
    }

    @Test
    void activeReportsNeverExpire() {
        var store = store(Duration.ofDays(30));
        var tracker = tracker(store);
        tracker.onConnected();
        clock.advance(Duration.ofDays(31));
        store.cleanup();
        assertThat(store.report("RM_history")).isPresent();
    }

    @Test
    void storageFailureKeepsTrackingButBlocksStaleReportUntilHistoryIsSaved() throws Exception {
        Path storage = directory.resolve("storage");
        var store = store(storage, Duration.ofDays(30));
        var tracker = tracker(store);
        tracker.onConnected();
        Path file = storage.resolve(tracker.reportSession().sessionId() + ".json");
        Files.delete(file);
        Files.createDirectory(file);
        Files.writeString(file.resolve("blocker"), "cannot replace a nonempty directory with a file");

        tracker.onEvent(event("join", PARTICIPANT_JOINED, "anna"));
        assertThat(tracker.snapshot().status()).isEqualTo(CallStatus.TRACKING);
        assertThat(tracker.snapshot().persistencePending()).isTrue();
        assertThatThrownBy(() -> store.report("RM_history")).isInstanceOf(ReportStorageException.class);
        clock.advance(Duration.ofMinutes(1));
        tracker.onEvent(event("leave", PARTICIPANT_LEFT, "anna"));
        assertThatThrownBy(tracker::checkpoint).isInstanceOf(ReportStorageException.class);
        assertThat(tracker.reportSession().stoppedAt()).isNull();
        Files.delete(file.resolve("blocker"));
        Files.delete(file);
        tracker.checkpoint();
        assertThat(tracker.snapshot().persistencePending()).isFalse();
        var saved = store.report("RM_history").orElseThrow().sessions().getFirst();
        assertThat(saved.visits()).hasSize(1);
        assertThat(saved.visits().getFirst().leftAt()).isEqualTo(clock.instant());
        assertThat(saved.status()).isEqualTo(CallStatus.TRACKING);
    }

    @Test
    void startupRejectsCorruptedHistoryInsteadOfSilentlyLosingIt() throws Exception {
        Files.writeString(directory.resolve(UUID.randomUUID() + ".json"), "invalid json");
        assertThatThrownBy(() -> store(Duration.ofDays(30))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void usesSessionUuidForFilenameAndNeverPersistsActivationCredentials() throws Exception {
        var store = store(Duration.ofDays(30));
        var registry = registry(store);
        var tracker = start(registry, "RM_history");
        tracker.onConnected();
        Path file = directory.resolve(tracker.reportSession().sessionId() + ".json");
        assertThat(file).exists();
        assertThat(Files.readString(file)).doesNotContain("activationSecret", "secret", "credentials");
    }

    @Test
    void secondStoreCannotRecoverSessionsWhileTheirOwnerIsRunning() throws Exception {
        var store = store(Duration.ofDays(30));
        var tracker = tracker(store);
        tracker.onConnected();
        Path file = directory.resolve(tracker.reportSession().sessionId() + ".json");
        String original = Files.readString(file);

        assertThatThrownBy(() -> store(Duration.ofDays(30)))
                .isInstanceOf(IllegalStateException.class)
                .hasStackTraceContaining("Attendance report directory is already in use");

        assertThat(Files.readString(file)).isEqualTo(original);
        assertThat(store.report("RM_history").orElseThrow().sessions().getFirst().status()).isEqualTo(CallStatus.TRACKING);
    }

    @Test
    void failedStartupReleasesItsDirectoryLock() throws Exception {
        Path corrupt = directory.resolve(UUID.randomUUID() + ".json");
        Files.writeString(corrupt, "invalid json");
        assertThatThrownBy(() -> store(Duration.ofDays(30))).isInstanceOf(IllegalStateException.class);
        Files.delete(corrupt);
        assertThat(store(Duration.ofDays(30)).report("RM_unknown")).isEmpty();
    }

    @Test
    void refusesToReturnAnArchiveReplacedWithAnotherCallsData() throws Exception {
        var store = store(Duration.ofDays(30));
        var tracker = tracker(store);
        tracker.onConnected();
        Path file = directory.resolve(tracker.reportSession().sessionId() + ".json");
        String changed = Files.readString(file).replace("RM_history", "RM_other");
        Files.writeString(file, changed);
        assertThatThrownBy(() -> store.report("RM_history")).isInstanceOf(ReportStorageException.class);
    }

    @Test
    void finishedSnapshotsRemainFrozenAcrossCleanupAndEviction() {
        var store = store(Duration.ofDays(30));
        var registry = registry(store);
        var tracker = start(registry, "RM_history");
        tracker.onConnected();
        clock.advance(Duration.ofSeconds(10));
        tracker.onEvent(event("end", CALL_ENDED, ""));
        var ended = store.report("RM_history").orElseThrow().sessions().getFirst();

        clock.advance(Duration.ofSeconds(30));
        registry.evictStale();
        assertThat(store.report("RM_history").orElseThrow().sessions().getFirst()).isEqualTo(ended);

        clock.advance(Duration.ofMinutes(1));
        registry.evictStale();
        assertThat(registry.find("RM_history")).isEmpty();
        assertThat(store.report("RM_history").orElseThrow().sessions().getFirst()).isEqualTo(ended);
    }

    @Test
    void reportsExpireAtTheConfiguredBoundary() {
        var store = store(Duration.ofDays(30));
        var tracker = tracker(store);
        tracker.onConnected();
        tracker.onEvent(event("end", CALL_ENDED, ""));
        clock.advance(Duration.ofDays(30));
        assertThat(store.report("RM_history")).isEmpty();
    }

    private AttendanceReportStore store(Duration retention) {
        return store(directory, retention);
    }

    private AttendanceReportStore store(Path path, Duration retention) {
        var store = new AttendanceReportStore(new ReportStorageProperties(path, retention), clock);
        stores.add(store);
        return store;
    }

    private CallTracker tracker(AttendanceReportStore store) {
        return new CallTracker("RM_history", "test", clock, List.of(), List.of(), UUID.randomUUID(),
                session -> store.save("RM_history", session));
    }

    private CallRegistry registry(AttendanceReportStore store) {
        return new CallRegistry(new RecordingEventSource(),
                new TrackingProperties(2, Duration.ofMinutes(1), Duration.ofSeconds(30), List.of()), clock, store);
    }

    private CallEvent event(String id, ru.itmo.plugins.attendance.service.events.CallEventType type, String participant) {
        return new CallEvent(id, type, "RM_history", participant, clock.instant());
    }
}
