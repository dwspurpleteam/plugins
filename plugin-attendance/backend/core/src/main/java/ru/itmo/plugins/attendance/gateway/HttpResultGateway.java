package ru.itmo.plugins.attendance.gateway;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Result Gateway по HTTP: {@code POST <url>/v1/results}, ответ 2xx — сообщение принято.
 *
 * <p>Ответ 4xx означает отказ, причина берётся из поля {@code reason} или {@code detail} тела.
 * Адрес для отчёта о доставке передаётся заголовком {@value #CALLBACK_HEADER}: его читает mock на WireMock,
 * настоящий Result Gateway будет узнавать адрес плагина из каталога.
 */
@Component
public class HttpResultGateway implements ResultGateway, DisposableBean {

    static final String CALLBACK_HEADER = "X-Callback-Url";
    private static final int MAX_REASON_LENGTH = 300;

    private final ResultGatewayProperties properties;
    private final JsonMapper json;
    private final Environment environment;
    private final URI endpoint;
    private final HttpClient http;

    public HttpResultGateway(ResultGatewayProperties properties, JsonMapper json, Environment environment) {
        this.properties = properties;
        this.json = json;
        this.environment = environment;
        this.endpoint = URI.create(properties.url().toString().replaceAll("/+$", "") + "/v1/results");
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(properties.connectTimeout())
                .build();
    }

    @Override
    public String describe() {
        return endpoint.toString();
    }

    @Override
    public SendResult send(ResultMessage<?> message) {
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(properties.readTimeout())
                .header("Content-Type", "application/json")
                .header(CALLBACK_HEADER, callbackUrl().toString())
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(message)))
                .build();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (HttpConnectTimeoutException e) {
            return new SendResult.Failed("Result Gateway не принял соединение за " + format(properties.connectTimeout()));
        } catch (HttpTimeoutException e) {
            return new SendResult.Failed("Result Gateway не ответил за " + format(properties.readTimeout()));
        } catch (ConnectException e) {
            return new SendResult.Failed("Result Gateway недоступен: " + properties.url());
        } catch (IOException e) {
            return new SendResult.Failed("Ошибка связи с Result Gateway: " + e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new SendResult.Failed("Отправка прервана");
        }

        int status = response.statusCode();
        if (status >= 200 && status < 300) {
            return new SendResult.Accepted();
        }
        String reason = reasonOf(response.body());
        if (status >= 400 && status < 500) {
            return new SendResult.Rejected(reason != null ? reason : "Result Gateway отклонил сообщение: HTTP " + status);
        }
        return new SendResult.Failed("Result Gateway ответил HTTP " + status + (reason != null ? ": " + reason : ""));
    }

    @Override
    public void destroy() {
        http.shutdownNow();
    }

    private URI callbackUrl() {
        if (properties.callbackUrl() != null) {
            return properties.callbackUrl();
        }
        // Порт известен только после старта веб-сервера: в тестах он случайный.
        String port = environment.getProperty("local.server.port", environment.getProperty("server.port", "8080"));
        return URI.create("http://localhost:" + port + "/platform/deliveries");
    }

    private String reasonOf(String body) {
        JsonNode node;
        try {
            node = json.readTree(body);
        } catch (JacksonException e) {
            return null;
        }
        for (String field : new String[] {"reason", "detail"}) {
            JsonNode value = node.path(field);
            if (value.isString() && !value.stringValue().isBlank()) {
                String text = value.stringValue();
                return text.length() > MAX_REASON_LENGTH ? text.substring(0, MAX_REASON_LENGTH) + "…" : text;
            }
        }
        return null;
    }

    private static String format(Duration duration) {
        return duration.toMillis() % 1000 == 0 ? duration.toSeconds() + " с" : duration.toMillis() + " мс";
    }
}
