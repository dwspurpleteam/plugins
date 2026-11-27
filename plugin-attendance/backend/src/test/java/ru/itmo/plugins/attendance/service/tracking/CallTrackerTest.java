package ru.itmo.plugins.attendance.service.tracking;

import static org.assertj.core.api.Assertions.assertThat;
import static ru.itmo.plugins.attendance.service.events.CallEventType.CALL_ENDED;
import static ru.itmo.plugins.attendance.service.events.CallEventType.PARTICIPANT_JOINED;
import static ru.itmo.plugins.attendance.service.events.CallEventType.PARTICIPANT_LEFT;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;

import ru.itmo.plugins.attendance.service.events.CallEvent;
import ru.itmo.plugins.attendance.service.events.CallEventType;
import ru.itmo.plugins.attendance.service.support.MutableClock;
import ru.itmo.plugins.attendance.service.support.RecordingEventSource.RecordedSubscription;
import ru.itmo.plugins.attendance.service.tracking.CallSnapshot.Gap;
import ru.itmo.plugins.attendance.service.tracking.CallSnapshot.Participant;

class CallTrackerTest {

    private static final Instant START = Instant.parse("2026-10-22T10:00:00Z");
    private static final String CALL = "RM_1";

    private final MutableClock clock = new MutableClock(START);
    private final CallTracker tracker = new CallTracker(CALL, "test", clock, List.of("streamapi-"));
    private int nextEventId;

    @Test
    void tracksSinceFirstConnection() {
        assertThat(tracker.snapshot().status()).isEqualTo(CallStatus.CONNECTING);

        clock.advance(Duration.ofSeconds(2));
        tracker.onConnected();

        CallSnapshot snapshot = tracker.snapshot();
        assertThat(snapshot.status()).isEqualTo(CallStatus.TRACKING);
        assertThat(snapshot.trackingSince()).isEqualTo(at(2));
        assertThat(snapshot.source()).isEqualTo("test");
    }

    @Test
    void initializesParticipantsOnceAtTrackingStart() {
        var seeded = new CallTracker(CALL, "test", clock, List.of("streamapi-"),
                List.of("anna", "anna", "boris", "streamapi-helper", " "));
        assertThat(seeded.snapshot().present()).isEmpty();

        clock.advance(Duration.ofSeconds(2));
        seeded.onConnected();
        assertThat(seeded.snapshot().present()).containsExactly(
                new Participant("anna", at(2), null), new Participant("boris", at(2), null));

        seeded.onEvent(event(PARTICIPANT_LEFT, "anna", 3));
        seeded.onDisconnected("temporary outage");
        clock.advance(Duration.ofSeconds(5));
        seeded.onConnected();

        assertThat(seeded.snapshot().trackingSince()).isEqualTo(at(2));
        assertThat(seeded.snapshot().present()).containsExactly(new Participant("boris", at(2), null));
        assertThat(seeded.snapshot().left()).containsExactly(new Participant("anna", at(2), at(3)));
    }

    @Test
    void preservesEventsReceivedBeforeInitialConnection() {
        var seeded = new CallTracker(CALL, "test", clock, List.of(), List.of("anna"));
        seeded.onEvent(event(PARTICIPANT_LEFT, "anna", 1));

        clock.advance(Duration.ofSeconds(2));
        seeded.onConnected();

        assertThat(seeded.snapshot().present()).isEmpty();
        assertThat(seeded.snapshot().left()).containsExactly(new Participant("anna", null, at(1)));
    }

    @Test
    void roundedCallEndCannotProduceNegativeInitialVisitDuration() {
        clock.advance(Duration.ofMillis(500));
        var seeded = new CallTracker(CALL, "test", clock, List.of(), List.of("anna"));
        seeded.onConnected();

        seeded.onEvent(event(CALL_ENDED, "", 0));

        assertThat(seeded.snapshot().status()).isEqualTo(CallStatus.ENDED);
        assertThat(seeded.snapshot().left()).containsExactly(
                new Participant("anna", clock.instant(), clock.instant()));
    }

    @Test
    void participantStaysPresentUntilLeft() {
        tracker.onEvent(event(PARTICIPANT_JOINED, "anna", 10));
        assertThat(tracker.snapshot().present()).containsExactly(new Participant("anna", at(10), null));

        tracker.onEvent(event(PARTICIPANT_LEFT, "anna", 70));

        CallSnapshot snapshot = tracker.snapshot();
        assertThat(snapshot.present()).isEmpty();
        assertThat(snapshot.left()).containsExactly(new Participant("anna", at(10), at(70)));
    }

    @Test
    void ignoresRedeliveredEvent() {
        CallEvent joined = new CallEvent("a", PARTICIPANT_JOINED, CALL, "anna", at(10));
        tracker.onEvent(joined);
        tracker.onEvent(new CallEvent("b", PARTICIPANT_LEFT, CALL, "anna", at(10)));

        tracker.onEvent(joined);

        assertThat(tracker.snapshot().present()).isEmpty();
        assertThat(tracker.snapshot().left()).containsExactly(new Participant("anna", at(10), at(10)));
    }

    @Test
    void repeatedJoinKeepsFirstJoinTime() {
        tracker.onEvent(event(PARTICIPANT_JOINED, "anna", 10));
        tracker.onEvent(event(PARTICIPANT_JOINED, "anna", 15));

        assertThat(tracker.snapshot().present()).containsExactly(new Participant("anna", at(10), null));
    }

    @Test
    void rejoinStartsNewVisit() {
        tracker.onEvent(event(PARTICIPANT_JOINED, "anna", 10));
        tracker.onEvent(event(PARTICIPANT_LEFT, "anna", 20));
        tracker.onEvent(event(PARTICIPANT_JOINED, "anna", 30));

        CallSnapshot snapshot = tracker.snapshot();
        assertThat(snapshot.present()).containsExactly(new Participant("anna", at(30), null));
        assertThat(snapshot.left()).isEmpty();
        assertThat(tracker.reportSession().visits()).containsExactly(
                new AttendanceReport.Visit("anna", at(10), at(20), false, "participant_left"),
                new AttendanceReport.Visit("anna", at(30), null, false, null));
    }

    @Test
    void reportDistinguishesSeededVisitsAndCallEndFromObservedEntryAndExit() {
        var seeded = new CallTracker(CALL, "test", clock, List.of(), List.of("anna"));
        seeded.onConnected();
        seeded.onEvent(event(PARTICIPANT_JOINED, "boris", 10));
        seeded.onEvent(event(PARTICIPANT_LEFT, "boris", 20));
        seeded.onEvent(event(CALL_ENDED, "", 30));

        assertThat(seeded.reportSession().visits()).containsExactly(
                new AttendanceReport.Visit("anna", START, at(30), true, "call_ended"),
                new AttendanceReport.Visit("boris", at(10), at(20), false, "participant_left"));
    }

    @Test
    void reportRetainsAllConnectionGaps() {
        tracker.onConnected();
        for (int i = 0; i < 25; i++) {
            tracker.onDisconnected("outage");
            clock.advance(Duration.ofSeconds(1));
            tracker.onConnected();
        }
        assertThat(tracker.reportSession().gaps()).hasSize(25);
    }

    @Test
    void reportSnapshotDoesNotChangeAfterFurtherEvents() {
        tracker.onEvent(event(PARTICIPANT_JOINED, "anna", 10));
        var report = tracker.reportSession();
        tracker.onEvent(event(PARTICIPANT_LEFT, "anna", 20));
        assertThat(report.visits()).containsExactly(new AttendanceReport.Visit("anna", at(10), null, false, null));
    }

    @Test
    void persistenceFailureDoesNotCloseLiveSubscriptionAndTerminalCallbacksStillCloseOutsideLock() {
        List<Consumer<CallTracker>> actions = List.of(
                candidate -> candidate.onEvent(event(PARTICIPANT_JOINED, "anna", 10)),
                candidate -> candidate.onEvent(event(CALL_ENDED, "", 10)),
                candidate -> candidate.onFailed("source failed"),
                candidate -> candidate.onDisconnected("network failed"),
                CallTracker::onConnected,
                CallTracker::close);
        for (var action : actions) {
            AtomicBoolean failWrites = new AtomicBoolean();
            AtomicBoolean subscriptionClosed = new AtomicBoolean();
            var candidate = new CallTracker(CALL, "test", clock, List.of(), List.of(), null, session -> {
                if (failWrites.get()) {
                    throw new ReportStorageException("disk full");
                }
            });
            candidate.onConnected();
            candidate.attach(() -> {
                assertThat(Thread.holdsLock(candidate)).as("SDK close must run outside tracker lock").isFalse();
                subscriptionClosed.set(true);
            });
            clock.advance(Duration.ofSeconds(1));
            failWrites.set(true);

            if (action == actions.getLast()) {
                org.assertj.core.api.Assertions.assertThatThrownBy(() -> action.accept(candidate))
                        .isInstanceOf(ReportStorageException.class);
            } else {
                action.accept(candidate);
            }
            var snapshot = candidate.snapshot();
            assertThat(snapshot.persistencePending()).isTrue();
            assertThat(subscriptionClosed.get()).isEqualTo(snapshot.stoppedAt() != null);
            if (snapshot.stoppedAt() == null) assertThat(snapshot.status()).isNotEqualTo(CallStatus.FAILED);
        }
    }

    @Test
    void failedFinalWriteCanBeRetriedWithoutChangingCallEndOrObservationTime() {
        var saved = new ArrayList<AttendanceReport.Session>();
        AtomicBoolean fail = new AtomicBoolean();
        var candidate = new CallTracker(CALL, "test", clock, List.of(), List.of(), null, session -> {
            if (fail.get()) throw new ReportStorageException("disk full");
            saved.add(session);
        });
        candidate.onConnected();
        clock.advance(Duration.ofSeconds(10));
        fail.set(true);
        candidate.onEvent(event(CALL_ENDED, "", 10));
        var ended = candidate.reportSession();
        assertThat(ended.status()).isEqualTo(CallStatus.ENDED);
        assertThat(ended.capturedAt()).isEqualTo(at(10));

        clock.advance(Duration.ofMinutes(5));
        fail.set(false);
        candidate.checkpoint();
        assertThat(saved.getLast()).isEqualTo(ended);
        assertThat(candidate.snapshot().persistencePending()).isFalse();
        int writes = saved.size();
        clock.advance(Duration.ofMinutes(5));
        candidate.checkpoint();
        candidate.close();
        assertThat(saved).hasSize(writes);
    }

    @Test
    void keepsEveryVisitDuringOutageAndRetriesWithLatestHistoryWithoutRestartingSubscription() {
        var saved = new ArrayList<AttendanceReport.Session>();
        AtomicBoolean fail = new AtomicBoolean();
        AtomicBoolean closed = new AtomicBoolean();
        int[] attempts = { 0 };
        var candidate = new CallTracker(CALL, "test", clock, List.of(), List.of(), null, session -> {
            attempts[0]++;
            if (fail.get()) throw new ReportStorageException("disk full");
            saved.add(session);
        });
        candidate.onConnected();
        candidate.attach(() -> closed.set(true));
        fail.set(true);
        candidate.onEvent(event(PARTICIPANT_JOINED, "anna", 0));
        int failedAttempts = attempts[0];
        clock.advance(Duration.ofSeconds(1));
        candidate.onEvent(event(PARTICIPANT_LEFT, "anna", 1));
        candidate.onEvent(event(PARTICIPANT_JOINED, "boris", 1));
        assertThat(attempts[0]).isEqualTo(failedAttempts);
        assertThat(candidate.snapshot().status()).isEqualTo(CallStatus.TRACKING);
        assertThat(candidate.snapshot().persistencePending()).isTrue();
        assertThat(candidate.snapshot().stoppedAt()).isNull();
        assertThat(closed).isFalse();

        clock.advance(Duration.ofSeconds(4));
        fail.set(false);
        candidate.onEvent(event(PARTICIPANT_JOINED, "anna", 5));
        assertThat(candidate.snapshot().persistencePending()).isFalse();
        assertThat(saved.getLast().visits()).hasSize(3);
        assertThat(saved.getLast().visits().getFirst().leftAt()).isEqualTo(at(1));
        assertThat(saved.getLast().visits()).extracting(AttendanceReport.Visit::participantId)
                .containsExactly("anna", "boris", "anna");
        assertThat(closed).isFalse();
    }

    @Test
    void connectedCallbackStillCompletesReadinessWhenStorageIsTemporarilyUnavailable() {
        AtomicBoolean fail = new AtomicBoolean(true);
        var candidate = new CallTracker(CALL, "test", clock, List.of(), List.of(), null, session -> {
            if (fail.get()) throw new ReportStorageException("disk full");
        });
        candidate.onConnected();
        assertThat(candidate.awaitConnected(Duration.ofMillis(1)).status()).isEqualTo(CallStatus.TRACKING);
        assertThat(candidate.snapshot().persistencePending()).isTrue();
        fail.set(false);
        candidate.checkpoint();
        assertThat(candidate.snapshot().persistencePending()).isFalse();
    }

    @Test
    void ignoresEventOlderThanAlreadyApplied() {
        tracker.onEvent(event(PARTICIPANT_LEFT, "anna", 20));
        tracker.onEvent(event(PARTICIPANT_JOINED, "anna", 10));

        assertThat(tracker.snapshot().present()).isEmpty();
    }

    @Test
    void leaveOfParticipantJoinedBeforeTrackingHasNoJoinTime() {
        tracker.onEvent(event(PARTICIPANT_LEFT, "boris", 20));

        assertThat(tracker.snapshot().left()).containsExactly(new Participant("boris", null, at(20)));
    }

    @Test
    void skipsServiceParticipantsAndOtherCalls() {
        tracker.onEvent(event(PARTICIPANT_JOINED, "streamapi-x7Kq", 10));
        tracker.onEvent(event(PARTICIPANT_JOINED, "", 10));
        tracker.onEvent(new CallEvent("other", PARTICIPANT_JOINED, "RM_2", "anna", at(10)));

        assertThat(tracker.snapshot().present()).isEmpty();
    }

    @Test
    void sortsPresentByJoinTimeAndLeftByLatestExit() {
        tracker.onEvent(event(PARTICIPANT_JOINED, "vera", 30));
        tracker.onEvent(event(PARTICIPANT_JOINED, "anna", 10));
        tracker.onEvent(event(PARTICIPANT_JOINED, "boris", 20));
        tracker.onEvent(event(PARTICIPANT_LEFT, "anna", 40));
        tracker.onEvent(event(PARTICIPANT_LEFT, "boris", 50));

        CallSnapshot snapshot = tracker.snapshot();
        assertThat(snapshot.present()).extracting(Participant::participantId).containsExactly("vera");
        assertThat(snapshot.left()).extracting(Participant::participantId).containsExactly("boris", "anna");
    }

    @Test
    void callEndClosesSubscriptionAndMarksEveryoneLeft() {
        RecordedSubscription subscription = new RecordedSubscription(CALL, tracker);
        tracker.attach(subscription);
        tracker.onConnected();
        tracker.onEvent(event(PARTICIPANT_JOINED, "anna", 10));
        tracker.onEvent(event(PARTICIPANT_JOINED, "boris", 20));
        tracker.onEvent(event(PARTICIPANT_LEFT, "boris", 30));

        tracker.onEvent(event(CALL_ENDED, "", 50));

        CallSnapshot snapshot = tracker.snapshot();
        assertThat(snapshot.status()).isEqualTo(CallStatus.ENDED);
        assertThat(snapshot.endedAt()).isEqualTo(at(50));
        assertThat(snapshot.present()).isEmpty();
        assertThat(snapshot.left()).containsExactly(
                new Participant("anna", at(10), at(50)),
                new Participant("boris", at(20), at(30)));
        assertThat(subscription.closed()).isTrue();

        tracker.onEvent(event(PARTICIPANT_JOINED, "vera", 60));
        tracker.onConnected();
        assertThat(tracker.snapshot().status()).isEqualTo(CallStatus.ENDED);
        assertThat(tracker.snapshot().present()).isEmpty();
    }

    @Test
    void disconnectAfterTrackingStartedOpensGapUntilReconnect() {
        tracker.onConnected();
        clock.advance(Duration.ofSeconds(10));

        tracker.onDisconnected("UNAVAILABLE: nats down");

        CallSnapshot interrupted = tracker.snapshot();
        assertThat(interrupted.status()).isEqualTo(CallStatus.RECONNECTING);
        assertThat(interrupted.statusMessage()).isEqualTo("UNAVAILABLE: nats down");
        assertThat(interrupted.gaps()).containsExactly(new Gap(at(10), null));

        clock.advance(Duration.ofSeconds(5));
        tracker.onConnected();

        CallSnapshot restored = tracker.snapshot();
        assertThat(restored.status()).isEqualTo(CallStatus.TRACKING);
        assertThat(restored.statusMessage()).isNull();
        assertThat(restored.trackingSince()).isEqualTo(START);
        assertThat(restored.gaps()).containsExactly(new Gap(at(10), at(15)));
    }

    @Test
    void disconnectBeforeFirstConnectionKeepsConnecting() {
        tracker.onDisconnected("UNAVAILABLE: connection refused");

        CallSnapshot snapshot = tracker.snapshot();
        assertThat(snapshot.status()).isEqualTo(CallStatus.CONNECTING);
        assertThat(snapshot.statusMessage()).isEqualTo("UNAVAILABLE: connection refused");
        assertThat(snapshot.trackingSince()).isNull();
        assertThat(snapshot.gaps()).isEmpty();
    }

    @Test
    void failureIsFinal() {
        RecordedSubscription subscription = new RecordedSubscription(CALL, tracker);
        tracker.attach(subscription);

        tracker.onFailed("INVALID_ARGUMENT: invalid event subscription");
        tracker.onConnected();

        CallSnapshot snapshot = tracker.snapshot();
        assertThat(snapshot.status()).isEqualTo(CallStatus.FAILED);
        assertThat(snapshot.statusMessage()).isEqualTo("INVALID_ARGUMENT: invalid event subscription");
        assertThat(subscription.closed()).isTrue();
    }

    @Test
    void failedSnapshotKeepsObservationStopTimeWhileServerClockAdvances() {
        tracker.onConnected();
        tracker.onEvent(event(PARTICIPANT_JOINED, "anna", 0));
        clock.advance(Duration.ofSeconds(10));
        tracker.onFailed("source failed");
        clock.advance(Duration.ofMinutes(1));

        assertThat(tracker.snapshot().stoppedAt()).isEqualTo(at(10));
        assertThat(tracker.snapshot().serverTime()).isEqualTo(at(70));
        assertThat(tracker.snapshot().present()).hasSize(1);
    }

    @Test
    void subscriptionAttachedAfterFinishIsClosed() {
        tracker.onFailed("UNIMPLEMENTED");
        RecordedSubscription subscription = new RecordedSubscription(CALL, tracker);

        tracker.attach(subscription);

        assertThat(subscription.closed()).isTrue();
    }

    @Test
    void retainsActiveCallsAndEvictsFailedCallsAfterRetention() {
        Duration failedRetention = Duration.ofMinutes(1);
        assertThat(tracker.shouldEvict(clock.instant(), failedRetention)).isFalse();

        clock.advance(Duration.ofMinutes(11));
        assertThat(tracker.shouldEvict(clock.instant(), failedRetention)).isFalse();

        CallTracker failed = new CallTracker("RM_2", "test", clock, List.of());
        failed.onFailed("UNIMPLEMENTED");
        clock.advance(Duration.ofSeconds(30));
        assertThat(failed.shouldEvict(clock.instant(), failedRetention)).isFalse();
        clock.advance(Duration.ofSeconds(31));
        assertThat(failed.shouldEvict(clock.instant(), failedRetention)).isTrue();
    }

    private CallEvent event(CallEventType type, String participantId, int second) {
        return new CallEvent("e" + nextEventId++, type, CALL, participantId, at(second));
    }

    private static Instant at(int second) {
        return START.plusSeconds(second);
    }
}
