package ru.itmo.plugins.attendance.tracking;

import java.time.Instant;
import java.util.List;

/**
 * Состав звонка на момент запроса; ответ {@code GET /api/calls/{callId}/participants}.
 *
 * @param trackingSince с какого момента учитываются входы; {@code null}, пока подписка не установилась
 * @param gaps          перерывы связи с источником, последний может быть ещё открыт
 * @param serverTime    время сервера, по нему клиент считает длительность без учёта своих часов
 * @param present       кто сейчас в звонке, по времени входа
 * @param left          кто покинул звонок, сначала последние
 */
public record CallSnapshot(
        String callId,
        String source,
        CallStatus status,
        String statusMessage,
        Instant trackingSince,
        Instant endedAt,
        List<Gap> gaps,
        Instant serverTime,
        List<Participant> present,
        List<Participant> left) {

    /**
     * @param joinedAt {@code null}: участник вошёл до начала отслеживания
     * @param leftAt   {@code null}: участник сейчас в звонке
     */
    public record Participant(String participantId, Instant joinedAt, Instant leftAt) {
    }

    /** @param to {@code null}: связь ещё не восстановлена */
    public record Gap(Instant from, Instant to) {
    }
}
