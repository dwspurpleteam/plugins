package ru.itmo.plugins.attendance.api;

import java.time.Duration;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import ru.itmo.plugins.attendance.events.CallEventType;
import ru.itmo.plugins.attendance.events.fake.FakeEventSource;

/** Ручное управление фейковым источником: доступно только с {@code attendance.source=fake}. */
@RestController
@RequestMapping("/dev/calls/{callId}")
@ConditionalOnProperty(prefix = "attendance", name = "source", havingValue = "fake")
class DevController {

    private static final int MAX_DROP_SECONDS = 300;

    private final FakeEventSource events;

    DevController(FakeEventSource events) {
        this.events = events;
    }

    /** Тело: {@code {"type": "participant.joined", "participantId": "anna-..."}}; participantId необязателен. */
    @PostMapping("/events")
    ResponseEntity<Void> emit(@PathVariable String callId, @RequestBody EventRequest request) {
        CallEventType type = CallEventType.fromWireName(request.type()).orElseThrow(() -> new ApiException(
                HttpStatus.BAD_REQUEST, "Неизвестный тип события: " + request.type()
                        + ". Допустимы: " + String.join(", ", CallEventType.wireNames())));
        String participantId = request.participantId() == null || request.participantId().isBlank()
                ? null
                : request.participantId().strip();
        if (!events.emit(CallIds.requireValid(callId), type, participantId)) {
            throw notTracked(callId);
        }
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/connection-loss")
    ResponseEntity<Void> dropConnection(@PathVariable String callId, @RequestParam(defaultValue = "5") int seconds) {
        if (seconds < 1 || seconds > MAX_DROP_SECONDS) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "seconds: от 1 до " + MAX_DROP_SECONDS);
        }
        if (!events.dropConnection(CallIds.requireValid(callId), Duration.ofSeconds(seconds))) {
            throw notTracked(callId);
        }
        return ResponseEntity.accepted().build();
    }

    private static ApiException notTracked(String callId) {
        return new ApiException(HttpStatus.NOT_FOUND,
                "Звонок " + callId + " не отслеживается: сначала откройте плагин с этим callId");
    }

    record EventRequest(String type, String participantId) {
    }
}
