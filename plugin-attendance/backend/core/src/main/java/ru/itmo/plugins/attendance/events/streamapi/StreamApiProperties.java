package ru.itmo.plugins.attendance.events.streamapi;

import java.time.Duration;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

import ru.itmo.plugins.attendance.config.PropertyChecks;

/**
 * @param target            адрес gRPC streamapi: снаружи {@code calls.vareonix.cc:443}, внутри стенда {@code nginx:19090}
 * @param plaintext         без TLS: локальный streamapi или внутренняя сеть стенда
 * @param metadata          дополнительные заголовки подписки, например cookie окружения stage
 * @param keepaliveTime     как часто проверять соединение пингами HTTP/2; не чаще 5 минут, иначе
 *                          gRPC-сервер streamapi при прямом подключении разорвёт соединение
 * @param keepaliveTimeout  сколько ждать ответа на пинг
 * @param reconnectMinDelay первая пауза перед переподключением; дальше удваивается
 * @param reconnectMaxDelay максимальная пауза перед переподключением
 */
@ConfigurationProperties("attendance.streamapi")
public record StreamApiProperties(
        String target,
        boolean plaintext,
        Map<String, String> metadata,
        Duration keepaliveTime,
        Duration keepaliveTimeout,
        Duration reconnectMinDelay,
        Duration reconnectMaxDelay) {

    static final String SECRET_PLACEHOLDER = "<secret>";

    public StreamApiProperties {
        if (target == null || target.isBlank()) {
            throw new IllegalArgumentException("attendance.streamapi.target must not be blank");
        }
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        metadata.forEach((name, value) -> {
            // Заглушка из файла профиля в git: с ней stage молча подписал бы плагин на prod.
            if (value.contains(SECRET_PLACEHOLDER)) {
                throw new IllegalArgumentException("attendance.streamapi.metadata." + name + " still contains "
                        + SECRET_PLACEHOLDER + ": put the real value into config/application-<profile>.yml");
            }
        });
        PropertyChecks.positive(keepaliveTime, "attendance.streamapi.keepalive-time");
        PropertyChecks.positive(keepaliveTimeout, "attendance.streamapi.keepalive-timeout");
        PropertyChecks.positive(reconnectMinDelay, "attendance.streamapi.reconnect-min-delay");
        PropertyChecks.positive(reconnectMaxDelay, "attendance.streamapi.reconnect-max-delay");
        if (reconnectMaxDelay.compareTo(reconnectMinDelay) < 0) {
            throw new IllegalArgumentException(
                    "attendance.streamapi.reconnect-max-delay must not be less than reconnect-min-delay");
        }
    }
}
