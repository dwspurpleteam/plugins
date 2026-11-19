package ru.itmo.plugins.attendance.configuration;

import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param target            адрес gRPC streamapi: снаружи {@code calls.vareonix.cc:443}, внутри стенда {@code nginx:19090}
 * @param plaintext         без TLS: локальный streamapi или внутренняя сеть стенда
 * @param metadata          дополнительные заголовки подписки, например cookie окружения stage
 */
@ConfigurationProperties("attendance.streamapi")
public record StreamApiProperties(
        String target,
        boolean plaintext,
        Map<String, String> metadata) {

    public StreamApiProperties {
        if (target == null || target.isBlank()) {
            throw new IllegalArgumentException("attendance.streamapi.target must not be blank");
        }
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
