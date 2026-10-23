package ru.itmo.plugins.attendance.summary;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;

import ru.itmo.plugins.attendance.events.CallEvent;
import ru.itmo.plugins.attendance.events.CallEventType;
import ru.itmo.plugins.attendance.events.EventSource;
import ru.itmo.plugins.attendance.gateway.Delivery;
import ru.itmo.plugins.attendance.gateway.ResultGateway;
import ru.itmo.plugins.attendance.gateway.ResultMessage;
import ru.itmo.plugins.attendance.support.MutableClock;
import ru.itmo.plugins.attendance.support.RecordingEventSource;
import ru.itmo.plugins.attendance.tracking.AttendanceSummary;
import ru.itmo.plugins.attendance.tracking.CallRegistry;
import ru.itmo.plugins.attendance.tracking.SummaryBoard;
import ru.itmo.plugins.attendance.tracking.SummaryBoard.Publication;
import ru.itmo.plugins.attendance.tracking.SummaryBoard.Status;
import ru.itmo.plugins.attendance.tracking.TrackingProperties;

class SummaryPublisherTest {

    private static final Instant START = Instant.parse("2026-10-23T10:00:00Z");
    private static final String CALL = "RM_1";

    private final MutableClock clock = new MutableClock(START);
    private final RecordingEventSource source = new RecordingEventSource();
    private final CallRegistry registry = new CallRegistry(source, new TrackingProperties(
            10, Duration.ofMinutes(10), Duration.ofMinutes(1), Duration.ofSeconds(30), List.of()), clock);
    private final RecordingGateway gateway = new RecordingGateway();
    private final SummaryPublisher publisher = new SummaryPublisher(registry, gateway, clock);

    @Test
    void sendsSummaryToEveryoneInCall() {
        EventSource.Listener call = startCall();
        call.onEvent(event(CallEventType.PARTICIPANT_JOINED, "anna", 1));
        call.onEvent(event(CallEventType.PARTICIPANT_JOINED, "boris", 2));
        call.onEvent(event(CallEventType.PARTICIPANT_JOINED, "vera", 3));
        call.onEvent(event(CallEventType.PARTICIPANT_LEFT, "vera", 4));
        clock.advance(Duration.ofSeconds(10));

        Publication publication = publisher.publish(CALL, "anna");

        assertThat(publication.status()).isEqualTo(Status.ACCEPTED);
        ResultMessage<?> message = gateway.messages.getFirst();
        assertThat(message.type()).isEqualTo("call.result");
        assertThat(message.key()).isEqualTo("attendance.summary");
        assertThat(message.callId()).isEqualTo(CALL);
        assertThat(message.messageId()).isEqualTo(publication.messageId());
        assertThat(message.revision()).isEqualTo(1);
        assertThat(message.recipients()).containsExactly("anna", "boris");
        assertThat(message.payload()).isEqualTo(new AttendanceSummary(START.plusSeconds(10), START, 2, 1, "anna"));
    }

    @Test
    void nextPublicationGetsNextRevision() {
        startCall();

        publisher.publish(CALL, null);
        publisher.publish(CALL, null);

        assertThat(gateway.messages).extracting(ResultMessage::revision).containsExactly(1L, 2L);
    }

    @Test
    void rejectionAndFailureBecomeStatuses() {
        startCall();

        gateway.next = new ResultGateway.SendResult.Rejected("Пустой список адресатов");
        assertThat(publisher.publish(CALL, null))
                .extracting(Publication::status, Publication::reason)
                .containsExactly(Status.REJECTED, "Пустой список адресатов");

        gateway.next = new ResultGateway.SendResult.Failed("Result Gateway недоступен");
        assertThat(publisher.publish(CALL, null))
                .extracting(Publication::status, Publication::reason)
                .containsExactly(Status.FAILED, "Result Gateway недоступен");
    }

    @Test
    void deliveredSummaryAppearsInSnapshot() {
        EventSource.Listener call = startCall();
        call.onEvent(event(CallEventType.PARTICIPANT_JOINED, "anna", 1));
        Publication publication = publisher.publish(CALL, "anna");
        @SuppressWarnings("unchecked")
        ResultMessage<AttendanceSummary> message = (ResultMessage<AttendanceSummary>) gateway.messages.getFirst();
        clock.advance(Duration.ofSeconds(2));

        assertThat(publisher.onDelivery(new Delivery<>(Delivery.Status.DELIVERED, null, message))).isTrue();

        SummaryBoard.View summary = registry.getOrStart(CALL).snapshot().summary();
        assertThat(summary.publication().status()).isEqualTo(Status.DELIVERED);
        assertThat(summary.delivered()).isEqualTo(new SummaryBoard.Delivered(
                publication.revision(), START.plusSeconds(2), 1, message.payload()));
    }

    @Test
    void deliveryForUntrackedCallIsReported() {
        ResultMessage<AttendanceSummary> message = new ResultMessage<>("call.result", "m-1", "RM_gone",
                List.of("anna"), SummaryPublisher.KEY, 1, new AttendanceSummary(START, START, 1, 0, null));

        assertThat(publisher.onDelivery(new Delivery<>(Delivery.Status.DELIVERED, null, message))).isFalse();
    }

    private EventSource.Listener startCall() {
        registry.getOrStart(CALL);
        EventSource.Listener listener = source.subscriptions().getLast().listener();
        listener.onConnected();
        return listener;
    }

    private static CallEvent event(CallEventType type, String participantId, int second) {
        return new CallEvent(type + "-" + participantId + "-" + second, type, CALL, participantId,
                START.plusSeconds(second));
    }

    private static final class RecordingGateway implements ResultGateway {
        private final List<ResultMessage<?>> messages = new CopyOnWriteArrayList<>();
        private volatile SendResult next = new SendResult.Accepted();

        @Override
        public String describe() {
            return "test";
        }

        @Override
        public SendResult send(ResultMessage<?> message) {
            messages.add(message);
            return next;
        }
    }
}
