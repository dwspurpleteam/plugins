package ru.itmo.plugins.attendance.service.tracking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static ru.itmo.plugins.attendance.service.support.TestActivations.start;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import ru.itmo.plugins.attendance.configuration.TrackingProperties;
import ru.itmo.plugins.attendance.service.events.CallEvent;
import ru.itmo.plugins.attendance.service.events.CallEventType;
import ru.itmo.plugins.attendance.service.support.MutableClock;
import ru.itmo.plugins.attendance.service.support.RecordingEventSource.RecordedSubscription;
import ru.itmo.plugins.attendance.service.support.RecordingEventSource;

class CallRegistryTest {
    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-22T10:00:00Z"));
    private final RecordingEventSource source = new RecordingEventSource();
    private final TrackingProperties properties = new TrackingProperties(
            2, Duration.ofMinutes(1), Duration.ofSeconds(30), List.of());
    private final CallRegistry registry = new CallRegistry(source, properties, clock);

    @Test
    void subscribesOncePerActivation() {
        CallTracker first = start(registry, "RM_1");
        assertThat(start(registry, "RM_1")).isSameAs(first);
        assertThat(source.subscriptions()).extracting(RecordedSubscription::callId).containsExactly("RM_1");
    }

    @Test
    void rejectsCallsOverLimit() {
        start(registry, "RM_1");
        start(registry, "RM_2");
        assertThatThrownBy(() -> start(registry, "RM_3")).isInstanceOf(TooManyCallsException.class);
        assertThat(registry.size()).isEqualTo(2);
    }

    @Test
    void activeSubscriptionSurvivesWithoutUi() {
        var tracker = start(registry, "RM_1");
        source.subscriptions().getFirst().listener().onConnected();
        clock.advance(Duration.ofHours(1));
        registry.evictStale();
        assertThat(registry.find("RM_1")).contains(tracker);
        assertThat(source.subscriptions().getFirst().closed()).isFalse();
    }

    @Test
    void failedActivationIsRemovedAfterRetentionAndCanBeStartedAgain() {
        start(registry, "RM_1");
        source.subscriptions().getFirst().listener().onFailed("UNIMPLEMENTED");
        clock.advance(Duration.ofMinutes(2));
        registry.evictStale();
        assertThat(registry.find("RM_1")).isEmpty();
        assertThat(start(registry, "RM_1").status()).isEqualTo(CallStatus.CONNECTING);
        assertThat(source.subscriptions()).hasSize(2);
    }

    @Test
    void endedCallIsRemovedAfterRetention() {
        var tracker = start(registry, "RM_1");
        tracker.onEvent(new CallEvent("end", CallEventType.CALL_ENDED, "RM_1", "", clock.instant()));
        clock.advance(Duration.ofMinutes(2));
        registry.evictStale();
        assertThat(registry.find("RM_1")).isEmpty();
        assertThat(source.subscriptions().getFirst().closed()).isTrue();
    }

    @Test
    void destroyClosesAllSubscriptions() {
        start(registry, "RM_1");
        start(registry, "RM_2");
        registry.destroy();
        assertThat(source.subscriptions()).allMatch(RecordedSubscription::closed);
        assertThat(registry.size()).isZero();
    }
}
