package ru.itmo.plugins.attendance.tracking;

import static org.assertj.core.api.Assertions.assertThat;
import static ru.itmo.plugins.attendance.events.CallEventType.CALL_ENDED;
import static ru.itmo.plugins.attendance.events.CallEventType.PARTICIPANT_JOINED;
import static ru.itmo.plugins.attendance.events.CallEventType.PARTICIPANT_LEFT;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import ru.itmo.plugins.attendance.events.CallEvent;
import ru.itmo.plugins.attendance.events.CallEventType;
import ru.itmo.plugins.attendance.support.MutableClock;
import ru.itmo.plugins.attendance.support.RecordingEventSource.RecordedSubscription;
import ru.itmo.plugins.attendance.tracking.CallSnapshot.Gap;
import ru.itmo.plugins.attendance.tracking.CallSnapshot.Participant;

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
    void subscriptionAttachedAfterFinishIsClosed() {
        tracker.onFailed("UNIMPLEMENTED");
        RecordedSubscription subscription = new RecordedSubscription(CALL, tracker);

        tracker.attach(subscription);

        assertThat(subscription.closed()).isTrue();
    }

    @Test
    void evictsIdleCallsAndFailedCallsAfterRetention() {
        Duration idleTimeout = Duration.ofMinutes(10);
        Duration failedRetention = Duration.ofMinutes(1);
        assertThat(tracker.shouldEvict(clock.instant(), idleTimeout, failedRetention)).isFalse();

        clock.advance(Duration.ofMinutes(11));
        assertThat(tracker.shouldEvict(clock.instant(), idleTimeout, failedRetention)).isTrue();

        CallTracker failed = new CallTracker("RM_2", "test", clock, List.of());
        failed.onFailed("UNIMPLEMENTED");
        clock.advance(Duration.ofSeconds(30));
        assertThat(failed.shouldEvict(clock.instant(), idleTimeout, failedRetention)).isFalse();
        clock.advance(Duration.ofSeconds(31));
        assertThat(failed.shouldEvict(clock.instant(), idleTimeout, failedRetention)).isTrue();
    }

    private CallEvent event(CallEventType type, String participantId, int second) {
        return new CallEvent("e" + nextEventId++, type, CALL, participantId, at(second));
    }

    private static Instant at(int second) {
        return START.plusSeconds(second);
    }
}
