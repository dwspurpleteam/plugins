package ru.itmo.plugins.attendance.summary;

import java.time.Clock;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import ru.itmo.plugins.attendance.gateway.Delivery;
import ru.itmo.plugins.attendance.gateway.ResultGateway;
import ru.itmo.plugins.attendance.gateway.ResultGateway.SendResult;
import ru.itmo.plugins.attendance.gateway.ResultMessage;
import ru.itmo.plugins.attendance.tracking.AttendanceSummary;
import ru.itmo.plugins.attendance.tracking.CallRegistry;
import ru.itmo.plugins.attendance.tracking.CallSnapshot;
import ru.itmo.plugins.attendance.tracking.CallTracker;
import ru.itmo.plugins.attendance.tracking.SummaryBoard;
import ru.itmo.plugins.attendance.tracking.SummaryBoard.Publication;
import ru.itmo.plugins.attendance.tracking.SummaryBoard.Status;

/**
 * Публикует сводку посещаемости через Result Gateway. Адресаты — кто сейчас в звонке.
 *
 * <p>Отправка синхронная: запрос UI ждёт ответа Result Gateway о приёме. Доставка асинхронная, отчёт о ней
 * принимает {@link #onDelivery}.
 */
@Service
public class SummaryPublisher {

    public static final String KEY = "attendance.summary";

    private static final Logger log = LoggerFactory.getLogger(SummaryPublisher.class);

    private final CallRegistry registry;
    private final ResultGateway gateway;
    private final Clock clock;

    public SummaryPublisher(CallRegistry registry, ResultGateway gateway, Clock clock) {
        this.registry = registry;
        this.gateway = gateway;
        this.clock = clock;
    }

    /** @param publishedBy participantId того, кто нажал кнопку; {@code null}, если неизвестен */
    public Publication publish(String callId, String publishedBy) {
        CallTracker tracker = registry.getOrStart(callId);
        CallSnapshot snapshot = tracker.snapshot();
        SummaryBoard board = tracker.summary();
        Publication started = board.start(clock.instant());

        List<String> recipients = snapshot.present().stream().map(CallSnapshot.Participant::participantId).toList();
        AttendanceSummary summary = new AttendanceSummary(snapshot.serverTime(), snapshot.trackingSince(),
                snapshot.present().size(), snapshot.left().size(), publishedBy);
        ResultMessage<AttendanceSummary> message = new ResultMessage<>(ResultMessage.TYPE_RESULT,
                started.messageId(), callId, recipients, KEY, started.revision(), summary);

        Publication publication = switch (gateway.send(message)) {
            case SendResult.Accepted() -> board.sent(started, Status.ACCEPTED, null, clock.instant());
            case SendResult.Rejected(String reason) -> board.sent(started, Status.REJECTED, reason, clock.instant());
            case SendResult.Failed(String reason) -> board.sent(started, Status.FAILED, reason, clock.instant());
        };
        String reason = publication.reason() == null ? "" : " (" + publication.reason() + ")";
        log.info("Summary of call {} revision {} for {} recipients: {}{}",
                callId, started.revision(), recipients.size(), publication.status(), reason);
        return publication;
    }

    /**
     * Отчёт Result Gateway о доставке.
     *
     * @return {@code false}, если звонок уже не отслеживается
     */
    public boolean onDelivery(Delivery<AttendanceSummary> delivery) {
        ResultMessage<AttendanceSummary> message = delivery.message();
        CallTracker tracker = registry.find(message.callId()).orElse(null);
        if (tracker == null) {
            log.info("Delivery report for call {} that is no longer tracked", message.callId());
            return false;
        }
        SummaryBoard board = tracker.summary();
        switch (delivery.status()) {
            case DELIVERED -> {
                int recipients = message.recipients() == null ? 0 : message.recipients().size();
                boolean shown = board.delivered(
                        message.messageId(), message.revision(), message.payload(), recipients, clock.instant());
                log.info("Summary of call {} revision {} delivered to {} recipients{}", message.callId(),
                        message.revision(), recipients, shown ? "" : ", newer revision already shown");
            }
            case REJECTED -> {
                board.rejected(message.messageId(), delivery.reason(), clock.instant());
                log.info("Summary of call {} revision {} rejected: {}", message.callId(), message.revision(),
                        delivery.reason());
            }
        }
        return true;
    }
}
