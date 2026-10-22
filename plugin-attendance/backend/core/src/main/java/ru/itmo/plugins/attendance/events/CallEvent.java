package ru.itmo.plugins.attendance.events;

import java.time.Instant;
import java.util.Objects;

/**
 * Событие звонка в формате, не зависящем от источника.
 *
 * @param id            идентификатор события для отсева повторов; пустой, если источник его не дал
 * @param participantId identity участника; пустой у событий самого звонка
 * @param occurredAt    время на платформе звонков; у LiveKit точность до секунды
 */
public record CallEvent(String id, CallEventType type, String callId, String participantId, Instant occurredAt) {

    public CallEvent {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(callId, "callId");
        Objects.requireNonNull(occurredAt, "occurredAt");
        id = id == null ? "" : id;
        participantId = participantId == null ? "" : participantId;
    }
}
