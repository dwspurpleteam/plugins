package ru.itmo.plugins.attendance.gateway;

import java.net.URI;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

import ru.itmo.plugins.attendance.config.PropertyChecks;

/**
 * @param url            адрес Result Gateway; для разработки — mock на WireMock
 * @param callbackUrl    куда Result Gateway сообщает о доставке; {@code null} — {@code /platform/deliveries}
 *                       этого процесса на localhost
 * @param connectTimeout сколько ждать соединения
 * @param readTimeout    сколько ждать ответа о приёме сообщения
 */
@ConfigurationProperties("attendance.result-gateway")
public record ResultGatewayProperties(
        URI url,
        URI callbackUrl,
        Duration connectTimeout,
        Duration readTimeout) {

    public ResultGatewayProperties {
        requireHttp(url, "attendance.result-gateway.url");
        if (callbackUrl != null) {
            requireHttp(callbackUrl, "attendance.result-gateway.callback-url");
        }
        PropertyChecks.positive(connectTimeout, "attendance.result-gateway.connect-timeout");
        PropertyChecks.positive(readTimeout, "attendance.result-gateway.read-timeout");
    }

    private static void requireHttp(URI value, String name) {
        if (value == null || !("http".equals(value.getScheme()) || "https".equals(value.getScheme()))
                || value.getHost() == null) {
            throw new IllegalArgumentException(name + " must be an absolute http(s) URL");
        }
    }
}
