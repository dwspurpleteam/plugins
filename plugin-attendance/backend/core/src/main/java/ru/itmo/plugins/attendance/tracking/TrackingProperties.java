package ru.itmo.plugins.attendance.tracking;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

import ru.itmo.plugins.attendance.config.PropertyChecks;

/**
 * @param maxCalls                   сколько звонков отслеживается одновременно
 * @param idleTimeout                через сколько без запросов UI отслеживание звонка останавливается
 * @param failedRetention            сколько хранится отказ источника, прежде чем следующий запрос попробует снова
 * @param cleanupInterval            как часто проверять устаревшие звонки
 * @param ignoredParticipantPrefixes служебные участники, которых не показываем (скрытый получатель streamapi, egress)
 */
@ConfigurationProperties("attendance.tracking")
public record TrackingProperties(
        int maxCalls,
        Duration idleTimeout,
        Duration failedRetention,
        Duration cleanupInterval,
        List<String> ignoredParticipantPrefixes) {

    public TrackingProperties {
        if (maxCalls < 1) {
            throw new IllegalArgumentException("attendance.tracking.max-calls must be positive");
        }
        PropertyChecks.positive(idleTimeout, "attendance.tracking.idle-timeout");
        PropertyChecks.positive(failedRetention, "attendance.tracking.failed-retention");
        PropertyChecks.positive(cleanupInterval, "attendance.tracking.cleanup-interval");
        ignoredParticipantPrefixes = ignoredParticipantPrefixes == null
                ? List.of()
                : ignoredParticipantPrefixes.stream().filter(prefix -> !prefix.isBlank()).toList();
    }
}
