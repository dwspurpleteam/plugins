package ru.itmo.plugins.attendance.service.tracking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static ru.itmo.plugins.attendance.service.support.TestActivations.start;
import static ru.itmo.plugins.attendance.service.support.TestActivations.command;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

import ru.itmo.plugins.attendance.configuration.TrackingProperties;
import ru.itmo.plugins.attendance.service.activation.ActivationCommand;
import ru.itmo.plugins.attendance.service.activation.ActivationException;
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

    @Test
    void failedFinalWriteRemainsRetryableBeforeActivationReplacement() {
        AtomicBoolean failWrites = new AtomicBoolean();
        var saved = new ArrayList<AttendanceReport.Session>();
        var store = mock(AttendanceReportStore.class);
        doAnswer(invocation -> {
            if (failWrites.get()) throw new ReportStorageException("disk full");
            saved.add(invocation.getArgument(1));
            return null;
        }).when(store).save(anyString(), any());
        var durableRegistry = new CallRegistry(source, properties, clock, store);
        ActivationCommand activation = command("RM_1");
        CallTracker first = durableRegistry.activate(activation).tracker();
        first.onConnected();
        first.onEvent(new CallEvent("join", CallEventType.PARTICIPANT_JOINED, "RM_1", "anna", clock.instant()));
        clock.advance(Duration.ofSeconds(10));
        failWrites.set(true);

        first.onFailed("source failed");
        var terminal = first.reportSession();
        assertThatThrownBy(() -> durableRegistry.activate(activation)).isInstanceOf(ReportStorageException.class);
        assertThat(durableRegistry.find("RM_1")).contains(first);

        clock.advance(Duration.ofMinutes(2));
        durableRegistry.evictStale();
        assertThat(durableRegistry.find("RM_1")).contains(first);
        ActivationCommand conflicting = new ActivationCommand(activation.schemaVersion(), activation.activationId(),
                activation.pluginId(), "changed-version", activation.registryId(), activation.callId(),
                activation.activationSecret(), activation.initialContext());
        assertThatThrownBy(() -> durableRegistry.activate(conflicting))
                .isInstanceOfSatisfying(ActivationException.class, exception ->
                        assertThat(exception.reason()).isEqualTo(ActivationException.Reason.CONFLICT));

        failWrites.set(false);
        CallTracker second = durableRegistry.activate(activation).tracker();
        assertThat(second).isNotSameAs(first);
        assertThat(durableRegistry.find("RM_1")).contains(second);
        assertThat(saved.stream().filter(session -> session.sessionId().equals(terminal.sessionId()))
                .filter(session -> session.status() == CallStatus.FAILED).toList()).containsExactly(terminal);
        assertThat(source.subscriptions()).hasSize(2);
        assertThat(source.subscriptions().getFirst().closed()).isTrue();
    }

    @Test
    void pendingEndedCallsStayWithinTrackerLimitAndAreEvictedOnlyAfterSuccessfulRetry() {
        AtomicBoolean failWrites = new AtomicBoolean();
        var saved = new ArrayList<AttendanceReport.Session>();
        var store = mock(AttendanceReportStore.class);
        doAnswer(invocation -> {
            if (failWrites.get()) throw new ReportStorageException("disk full");
            saved.add(invocation.getArgument(1));
            return null;
        }).when(store).save(anyString(), any());
        var durableRegistry = new CallRegistry(source, properties, clock, store);
        var first = durableRegistry.activate(command("RM_1")).tracker();
        var second = durableRegistry.activate(command("RM_2")).tracker();
        first.onConnected();
        second.onConnected();
        failWrites.set(true);
        first.onEvent(new CallEvent("end1", CallEventType.CALL_ENDED, "RM_1", "", clock.instant()));
        second.onEvent(new CallEvent("end2", CallEventType.CALL_ENDED, "RM_2", "", clock.instant()));
        var terminal = first.reportSession();
        clock.advance(Duration.ofMinutes(2));
        durableRegistry.evictStale();
        assertThat(durableRegistry.size()).isEqualTo(2);
        assertThat(first.snapshot().persistencePending()).isTrue();
        assertThat(first.snapshot().status()).isEqualTo(CallStatus.ENDED);
        assertThat(source.subscriptions()).allMatch(RecordedSubscription::closed);
        assertThatThrownBy(() -> durableRegistry.activate(command("RM_3")))
                .isInstanceOf(TooManyCallsException.class);

        failWrites.set(false);
        durableRegistry.evictStale();
        assertThat(durableRegistry.size()).isZero();
        assertThat(saved).contains(terminal);
        assertThat(first.snapshot().persistencePending()).isFalse();
        assertThat(first.reportSession()).isEqualTo(terminal);
    }

    @Test
    void initialWriteFailureDoesNotStartSubscriptionAndActivationCanBeRetried() {
        AtomicBoolean failWrites = new AtomicBoolean(true);
        var store = mock(AttendanceReportStore.class);
        doAnswer(invocation -> {
            if (failWrites.get()) throw new ReportStorageException("disk full");
            return null;
        }).when(store).save(anyString(), any());
        var durableRegistry = new CallRegistry(source, properties, clock, store);
        var activation = command("RM_1");
        assertThatThrownBy(() -> durableRegistry.activate(activation)).isInstanceOf(ReportStorageException.class);
        assertThat(durableRegistry.size()).isZero();
        assertThat(source.subscriptions()).isEmpty();
        failWrites.set(false);
        durableRegistry.activate(activation);
        assertThat(durableRegistry.size()).isEqualTo(1);
        assertThat(source.subscriptions()).hasSize(1);
    }
}
