package ru.itmo.plugins.attendance.configuration;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

import ru.itmo.plugins.attendance.configuration.PropertyChecks;

/**
 * @param maxCalls                   сколько звонков отслеживается одновременно
 * @param failedRetention            сколько хранится состояние завершённой или отказавшей активации
 * @param cleanupInterval            как часто проверять устаревшие звонки
 * @param ignoredParticipantPrefixes служебные участники, которых не показываем (скрытый получатель streamapi, egress)
 */
@ConfigurationProperties("attendance.tracking")
public record TrackingProperties(
        int maxCalls,
        Duration failedRetention,
        Duration cleanupInterval,
        List<String> ignoredParticipantPrefixes) {

    public TrackingProperties {
        if (maxCalls < 1) {
            throw new IllegalArgumentException("attendance.tracking.max-calls must be positive");
        }
        PropertyChecks.positive(failedRetention, "attendance.tracking.failed-retention");
        PropertyChecks.positive(cleanupInterval, "attendance.tracking.cleanup-interval");
        ignoredParticipantPrefixes = ignoredParticipantPrefixes == null
                ? List.of()
                : ignoredParticipantPrefixes.stream().filter(prefix -> !prefix.isBlank()).toList();
    }
}
