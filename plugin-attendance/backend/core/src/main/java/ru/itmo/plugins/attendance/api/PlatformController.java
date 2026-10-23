package ru.itmo.plugins.attendance.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import ru.itmo.plugins.attendance.gateway.Delivery;
import ru.itmo.plugins.attendance.gateway.ResultMessage;
import ru.itmo.plugins.attendance.summary.SummaryPublisher;
import ru.itmo.plugins.attendance.tracking.AttendanceSummary;

/** Запросы платформы к плагину. Подписи пока нет: отчёт о доставке может прислать кто угодно. */
@RestController
@RequestMapping("/platform")
class PlatformController {

    private final SummaryPublisher publisher;

    PlatformController(SummaryPublisher publisher) {
        this.publisher = publisher;
    }

    /** Отчёт Result Gateway о доставке результата. */
    @PostMapping("/deliveries")
    ResponseEntity<Void> delivery(@RequestBody Delivery<AttendanceSummary> delivery) {
        ResultMessage<AttendanceSummary> message = delivery.message();
        if (delivery.status() == null || message == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Нужны поля status и message");
        }
        CallIds.requireValid(message.callId());
        if (!SummaryPublisher.KEY.equals(message.key())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Неизвестный ключ результата: " + message.key());
        }
        if (message.messageId() == null || message.messageId().isBlank() || message.revision() < 1) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Нужны messageId и положительная revision");
        }
        if (delivery.status() == Delivery.Status.DELIVERED && message.payload() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "В доставленном сообщении нет payload");
        }
        if (!publisher.onDelivery(delivery)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Звонок " + message.callId() + " не отслеживается");
        }
        return ResponseEntity.noContent().build();
    }
}
