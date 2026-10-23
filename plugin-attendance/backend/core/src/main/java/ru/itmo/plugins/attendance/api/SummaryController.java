package ru.itmo.plugins.attendance.api;

import java.nio.charset.StandardCharsets;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import ru.itmo.plugins.attendance.summary.SummaryPublisher;
import ru.itmo.plugins.attendance.tracking.SummaryBoard.Publication;

@RestController
@RequestMapping(path = "/api", produces = MediaType.APPLICATION_JSON_VALUE)
class SummaryController {

    private final SummaryPublisher publisher;

    SummaryController(SummaryPublisher publisher) {
        this.publisher = publisher;
    }

    /**
     * Публикует сводку через Result Gateway и возвращает статус публикации. Отказ и недоступность Result Gateway —
     * это статусы, а не ошибки запроса. Без тела, чтобы запрос из sandbox-iframe оставался простым, без preflight.
     */
    @PostMapping("/calls/{callId}/summary")
    Publication publish(@PathVariable String callId, @RequestParam(required = false) String participantId) {
        return publisher.publish(CallIds.requireValid(callId), participantIdOrNull(participantId));
    }

    private static String participantIdOrNull(String participantId) {
        String value = participantId == null ? "" : participantId.strip();
        if (value.isEmpty()) {
            return null;
        }
        if (value.getBytes(StandardCharsets.UTF_8).length > CallIds.MAX_BYTES
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Некорректный participantId");
        }
        return value;
    }
}
