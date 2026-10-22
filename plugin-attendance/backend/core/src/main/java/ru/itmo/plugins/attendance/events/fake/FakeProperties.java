package ru.itmo.plugins.attendance.events.fake;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

import ru.itmo.plugins.attendance.config.PropertyChecks;

/**
 * @param connectDelay        задержка «подключения» подписки
 * @param initialParticipants сколько участников войдёт сразу после подключения
 * @param eventInterval       как часто случайный участник входит или выходит; 0 отключает генерацию
 * @param maxParticipants     больше этого числа участников генератор не добавляет
 */
@ConfigurationProperties("attendance.fake")
public record FakeProperties(
        Duration connectDelay,
        int initialParticipants,
        Duration eventInterval,
        int maxParticipants) {

    public FakeProperties {
        PropertyChecks.notNegative(connectDelay, "attendance.fake.connect-delay");
        PropertyChecks.notNegative(eventInterval, "attendance.fake.event-interval");
        if (initialParticipants < 0) {
            throw new IllegalArgumentException("attendance.fake.initial-participants must not be negative");
        }
        if (maxParticipants < 1) {
            throw new IllegalArgumentException("attendance.fake.max-participants must be positive");
        }
    }
}
