package ru.itmo.plugins.attendance.tracking;

import java.time.Instant;

/**
 * Сводка посещаемости: содержимое результата {@code attendance.summary}, которое плагин публикует через
 * Result Gateway.
 *
 * @param composedAt    когда собрана
 * @param trackingSince с какого момента учитываются входы
 * @param present       сколько участников в звонке
 * @param left          сколько покинули звонок
 * @param publishedBy   кто опубликовал: participantId из адреса iframe, не проверяется
 */
public record AttendanceSummary(
        Instant composedAt,
        Instant trackingSince,
        int present,
        int left,
        String publishedBy) {
}
