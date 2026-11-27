package ru.itmo.plugins.attendance.service.tracking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static ru.itmo.plugins.attendance.service.support.TestActivations.command;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import ru.itmo.plugins.attendance.configuration.ReportStorageProperties;
import ru.itmo.plugins.attendance.configuration.TrackingProperties;
import ru.itmo.plugins.attendance.service.activation.ActivationCommand;
import ru.itmo.plugins.attendance.service.activation.ActivationCredentials;
import ru.itmo.plugins.attendance.service.activation.ActivationException;
import ru.itmo.plugins.attendance.service.activation.DeactivationCommand;
import ru.itmo.plugins.attendance.service.events.CallEvent;
import ru.itmo.plugins.attendance.service.events.CallEventType;
import ru.itmo.plugins.attendance.service.events.EventSource;
import ru.itmo.plugins.attendance.service.report.AttendanceReportRenderer;
import ru.itmo.plugins.attendance.service.support.MutableClock;
import ru.itmo.plugins.attendance.service.support.RecordingEventSource;

class DeactivationTest {
    @TempDir Path directory;
    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-27T10:00:00Z"));
    private final RecordingEventSource source = new RecordingEventSource();
    private final TrackingProperties properties = new TrackingProperties(
            10, Duration.ofMinutes(1), Duration.ofSeconds(30), List.of());

    @Test
    void stopsOnlyTheRequestedActivationAndFreezesTheDurableReport() {
        try (var store = store(Duration.ofDays(30))) {
            var registry = new CallRegistry(source, properties, clock, store);
            var command = command("RM_1");
            var tracker = registry.activate(command).tracker();
            var other = registry.activate(command("RM_2")).tracker();
            tracker.onConnected();
            other.onConnected();
            tracker.onEvent(new CallEvent("join", CallEventType.PARTICIPANT_JOINED, "RM_1", "anna", clock.instant()));
            clock.advance(Duration.ofSeconds(15));
            registry.deactivate(stop(command));
            var snapshot = tracker.snapshot();
            var report = store.report("RM_1").orElseThrow();
            var stopped = report.sessions().getFirst();
            assertThat(snapshot.status()).isEqualTo(CallStatus.STOPPED);
            assertThat(snapshot.endedAt()).isNull();
            assertThat(snapshot.stoppedAt()).isEqualTo(clock.instant());
            assertThat(snapshot.present()).hasSize(1);
            assertThat(snapshot.left()).isEmpty();
            assertThat(stopped.visits().getFirst().leftAt()).isNull();
            assertThat(source.subscriptions().getFirst().closed()).isTrue();
            assertThat(source.subscriptions().get(1).closed()).isFalse();
            assertThat(other.status()).isEqualTo(CallStatus.TRACKING);

            clock.advance(Duration.ofMinutes(10));
            tracker.onConnected();
            tracker.onEvent(new CallEvent("late", CallEventType.PARTICIPANT_JOINED, "RM_1", "late", clock.instant()));
            registry.deactivate(stop(command));
            assertThat(store.report("RM_1").orElseThrow().sessions()).containsExactly(stopped);
            String csv = new String(new AttendanceReportRenderer().csv(store.report("RM_1").orElseThrow(),
                    ZoneId.of("UTC")), java.nio.charset.StandardCharsets.UTF_8);
            assertThat(csv).contains("10:00:00–10:00:15*", "15 с").doesNotContain("сейчас", "late");
            registry.destroy();
        }
    }

    @Test
    void rejectsMismatchedCallAndSecretAndOldStopCannotAffectTheNewActivation() {
        var registry = new CallRegistry(source, properties, clock);
        var original = command("RM_1");
        var tracker = registry.activate(original).tracker();
        tracker.onConnected();
        var valid = stop(original);
        var wrongCall = new DeactivationCommand("1.0", "deactivate", original.activationId(),
                "RM_other", "attendance", "moderator", original.activationSecret());
        var wrongSecret = new DeactivationCommand("1.0", "deactivate", original.activationId(),
                original.callId(), "attendance", "moderator", "wrong-secret");
        for (var invalid : List.of(wrongCall, wrongSecret)) {
            assertThatThrownBy(() -> registry.deactivate(invalid)).isInstanceOf(ActivationException.class);
            assertThat(tracker.status()).isEqualTo(CallStatus.TRACKING);
            assertThat(source.subscriptions().getFirst().closed()).isFalse();
        }
        registry.deactivate(valid);
        var replacement = replacement(original);
        var newTracker = registry.activate(replacement).tracker();
        newTracker.onConnected();
        registry.deactivate(valid);
        assertThat(newTracker.status()).isEqualTo(CallStatus.TRACKING);
        assertThat(source.subscriptions().get(1).closed()).isFalse();
        assertThatThrownBy(() -> registry.activate(original)).isInstanceOf(ActivationException.class);
        registry.destroy();
    }

    @Test
    void stopFencesSurviveRestartAndReportRetentionIncludingStopBeforeActivation() throws Exception {
        var original = command("RM_1");
        var neverStarted = command("RM_unstarted");
        try (var store = store(Duration.ofSeconds(1))) {
            var registry = new CallRegistry(source, properties, clock, store);
            var tracker = registry.activate(original).tracker();
            tracker.onConnected();
            registry.deactivate(stop(original));
            registry.deactivate(stop(neverStarted));
            registry.destroy();
        }
        String receipt = Files.readString(directory.resolve("deactivations").resolve(original.activationId() + ".json"));
        assertThat(receipt).doesNotContain(original.activationSecret());
        try (var store = store(Duration.ofSeconds(1))) {
            var registry = new CallRegistry(source, properties, clock, store);
            assertThat(store.report("RM_1").orElseThrow().sessions().getFirst().status()).isEqualTo(CallStatus.STOPPED);
            clock.advance(Duration.ofSeconds(2));
            store.cleanup();
            assertThat(store.report("RM_1")).isEmpty();
            assertThat(store.report("RM_unstarted")).isEmpty();
            assertThatThrownBy(() -> registry.activate(original)).isInstanceOf(ActivationException.class);
            assertThatThrownBy(() -> registry.activate(neverStarted)).isInstanceOf(ActivationException.class);
            registry.deactivate(stop(original));
            registry.deactivate(stop(neverStarted));
            assertThat(source.subscriptions()).hasSize(1);
            assertThat(registry.activate(replacement(original)).tracker().status()).isEqualTo(CallStatus.CONNECTING);
            registry.destroy();
        }
    }

    @Test
    void failedFinalWriteClosesSubscriptionButDoesNotAcknowledgeUntilRetrySucceeds() {
        var store = mock(AttendanceReportStore.class);
        var fail = new AtomicBoolean();
        var saved = new ArrayList<AttendanceReport.Session>();
        doAnswer(invocation -> {
            if (fail.get()) throw new ReportStorageException("disk full");
            saved.add(invocation.getArgument(1));
            return null;
        }).when(store).save(anyString(), any());
        var registry = new CallRegistry(source, properties, clock, store);
        var activation = command("RM_1");
        var tracker = registry.activate(activation).tracker();
        tracker.onConnected();
        fail.set(true);
        assertThatThrownBy(() -> registry.deactivate(stop(activation))).isInstanceOf(ReportStorageException.class);
        var terminal = tracker.reportSession();
        assertThat(source.subscriptions().getFirst().closed()).isTrue();
        assertThat(tracker.snapshot().persistencePending()).isTrue();
        assertThatThrownBy(() -> registry.activate(activation)).isInstanceOf(ActivationException.class);
        assertThatThrownBy(() -> registry.activate(replacement(activation))).isInstanceOf(ReportStorageException.class);
        registry.evictStale();
        assertThat(registry.find("RM_1")).contains(tracker);
        clock.advance(Duration.ofSeconds(30));
        fail.set(false);
        registry.deactivate(stop(activation));
        assertThat(saved.getLast()).isEqualTo(terminal);
        assertThat(tracker.snapshot().persistencePending()).isFalse();
        registry.destroy();
    }

    @Test
    void failedFenceWriteStillStopsTrackingAndBlocksDelayedStartUntilDeliveryIsRetried() {
        var store = mock(AttendanceReportStore.class);
        var fail = new AtomicBoolean(true);
        doAnswer(invocation -> {
            if (fail.get()) throw new ReportStorageException("disk full");
            return null;
        }).when(store).saveDeactivation(any());
        var registry = new CallRegistry(source, properties, clock, store);
        var activation = command("RM_1");
        var tracker = registry.activate(activation).tracker();
        tracker.onConnected();
        assertThatThrownBy(() -> registry.deactivate(stop(activation))).isInstanceOf(ReportStorageException.class);
        assertThat(source.subscriptions().getFirst().closed()).isTrue();
        registry.evictStale();
        assertThatThrownBy(() -> registry.activate(activation)).isInstanceOf(ActivationException.class);
        fail.set(false);
        registry.deactivate(stop(activation));
        registry.destroy();
    }

    @Test
    void subscriptionClosureFailureIsRetriedAndNotEvictedAsCompleted() {
        var closures = new AtomicInteger();
        EventSource failingSource = new EventSource() {
            public String name() { return "test"; }
            public String describe() { return "test"; }
            public Subscription subscribe(String callId, ActivationCredentials credentials, Listener listener) {
                return () -> {
                    if (closures.incrementAndGet() == 1) throw new IllegalStateException("close failed");
                };
            }
        };
        try (var store = store(Duration.ofDays(30))) {
            var registry = new CallRegistry(failingSource, properties, clock, store);
            var activation = command("RM_1");
            var tracker = registry.activate(activation).tracker();
            tracker.onConnected();
            clock.advance(Duration.ofSeconds(15));
            assertThatThrownBy(() -> registry.deactivate(stop(activation))).isInstanceOf(ActivationException.class);
            var terminal = store.report("RM_1").orElseThrow().sessions().getFirst();
            assertThat(terminal.status()).isEqualTo(CallStatus.STOPPED);
            assertThat(terminal.stoppedAt()).isEqualTo(clock.instant());
            registry.evictStale();
            assertThat(registry.find("RM_1")).contains(tracker);
            clock.advance(Duration.ofSeconds(15));
            registry.deactivate(stop(activation));
            assertThat(closures.get()).isEqualTo(2);
            assertThat(store.report("RM_1").orElseThrow().sessions()).containsExactly(terminal);
            registry.destroy();
        }
    }

    private AttendanceReportStore store(Duration retention) {
        return new AttendanceReportStore(new ReportStorageProperties(directory, retention), clock);
    }

    private static DeactivationCommand stop(ActivationCommand command) {
        return new DeactivationCommand("1.0", "deactivate", command.activationId(), command.callId(),
                command.pluginId(), "moderator", command.activationSecret());
    }

    private static ActivationCommand replacement(ActivationCommand original) {
        return new ActivationCommand(original.schemaVersion(), UUID.randomUUID(), original.pluginId(),
                original.pluginVersion(), original.registryId(), original.callId(), "new-secret", original.initialContext());
    }
}
