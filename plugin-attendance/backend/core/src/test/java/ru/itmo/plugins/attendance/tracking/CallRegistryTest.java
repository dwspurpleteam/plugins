package ru.itmo.plugins.attendance.tracking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import ru.itmo.plugins.attendance.support.MutableClock;
import ru.itmo.plugins.attendance.support.RecordingEventSource;
import ru.itmo.plugins.attendance.support.RecordingEventSource.RecordedSubscription;

class CallRegistryTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-22T10:00:00Z"));
    private final RecordingEventSource source = new RecordingEventSource();
    private final TrackingProperties properties = new TrackingProperties(
            2, Duration.ofMinutes(10), Duration.ofMinutes(1), Duration.ofSeconds(30), List.of());
    private final CallRegistry registry = new CallRegistry(source, properties, clock);

    @Test
    void subscribesOncePerCall() {
        CallTracker first = registry.getOrStart("RM_1");
        CallTracker second = registry.getOrStart("RM_1");

        assertThat(second).isSameAs(first);
        assertThat(source.subscriptions()).extracting(RecordedSubscription::callId).containsExactly("RM_1");
    }

    @Test
    void rejectsCallsOverLimit() {
        registry.getOrStart("RM_1");
        registry.getOrStart("RM_2");

        assertThatThrownBy(() -> registry.getOrStart("RM_3")).isInstanceOf(TooManyCallsException.class);
        assertThat(registry.size()).isEqualTo(2);
    }

    @Test
    void stopsTrackingCallNobodyLooksAt() {
        registry.getOrStart("RM_1");
        clock.advance(Duration.ofMinutes(11));

        registry.evictStale();

        assertThat(registry.size()).isZero();
        assertThat(source.subscriptions().getFirst().closed()).isTrue();

        registry.getOrStart("RM_1");
        assertThat(source.subscriptions()).hasSize(2);
    }

    @Test
    void keepsCallWhileUiRequestsIt() {
        registry.getOrStart("RM_1");
        clock.advance(Duration.ofMinutes(9));
        registry.getOrStart("RM_1");
        clock.advance(Duration.ofMinutes(9));

        registry.evictStale();

        assertThat(registry.size()).isEqualTo(1);
        assertThat(source.subscriptions().getFirst().closed()).isFalse();
    }

    @Test
    void retriesFailedCallAfterRetention() {
        registry.getOrStart("RM_1");
        source.subscriptions().getFirst().listener().onFailed("UNIMPLEMENTED");
        clock.advance(Duration.ofMinutes(2));

        registry.evictStale();
        CallTracker tracker = registry.getOrStart("RM_1");

        assertThat(tracker.status()).isEqualTo(CallStatus.CONNECTING);
        assertThat(source.subscriptions()).hasSize(2);
    }

    @Test
    void destroyClosesAllSubscriptions() {
        registry.getOrStart("RM_1");
        registry.getOrStart("RM_2");

        registry.destroy();

        assertThat(source.subscriptions()).allMatch(RecordedSubscription::closed);
        assertThat(registry.size()).isZero();
    }
}
