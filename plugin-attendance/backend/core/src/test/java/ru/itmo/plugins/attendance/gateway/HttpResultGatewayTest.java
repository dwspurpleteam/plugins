package ru.itmo.plugins.attendance.gateway;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import com.github.tomakehurst.wiremock.WireMockServer;

import ru.itmo.plugins.attendance.gateway.ResultGateway.SendResult;
import tools.jackson.databind.json.JsonMapper;

class HttpResultGatewayTest {

    private static final URI CALLBACK = URI.create("http://localhost:8081/platform/deliveries");
    private static final WireMockServer server = new WireMockServer(options().dynamicPort());

    private final JsonMapper json = JsonMapper.builder().build();
    private final ResultMessage<Map<String, Integer>> message = new ResultMessage<>(
            "call.result", "m-1", "RM_1", List.of("anna"), "attendance.summary", 3, Map.of("present", 1));

    @BeforeAll
    static void startServer() {
        server.start();
    }

    @AfterAll
    static void stopServer() {
        server.stop();
    }

    @BeforeEach
    void resetServer() {
        server.resetAll();
    }

    @Test
    void postsMessageWithCallbackAddress() {
        server.stubFor(post("/v1/results").willReturn(aResponse().withStatus(202)));

        assertThat(gateway(CALLBACK).send(message)).isEqualTo(new SendResult.Accepted());

        server.verify(postRequestedFor(urlEqualTo("/v1/results"))
                .withHeader("Content-Type", containing("application/json"))
                .withHeader("X-Callback-Url", equalTo(CALLBACK.toString()))
                .withRequestBody(equalToJson("""
                        {
                          "type": "call.result",
                          "messageId": "m-1",
                          "callId": "RM_1",
                          "recipients": ["anna"],
                          "key": "attendance.summary",
                          "revision": 3,
                          "payload": {"present": 1}
                        }
                        """)));
    }

    @Test
    void callbackDefaultsToThisServerOnLocalhost() {
        server.stubFor(post("/v1/results").willReturn(aResponse().withStatus(202)));
        MockEnvironment environment = new MockEnvironment().withProperty("local.server.port", "18081");

        new HttpResultGateway(properties(server.baseUrl(), null, Duration.ofSeconds(2)), json, environment).send(message);

        server.verify(postRequestedFor(urlEqualTo("/v1/results"))
                .withHeader("X-Callback-Url", equalTo("http://localhost:18081/platform/deliveries")));
    }

    @Test
    void keepsPathOfGatewayUrl() {
        server.stubFor(post("/gateway/v1/results").willReturn(aResponse().withStatus(202)));
        HttpResultGateway gateway = new HttpResultGateway(
                properties(server.baseUrl() + "/gateway/", CALLBACK, Duration.ofSeconds(2)), json, new MockEnvironment());

        assertThat(gateway.send(message)).isEqualTo(new SendResult.Accepted());
        assertThat(gateway.describe()).isEqualTo(server.baseUrl() + "/gateway/v1/results");
    }

    @Test
    void rejectionReasonComesFromResponse() {
        server.stubFor(post("/v1/results").willReturn(aResponse().withStatus(422)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"status\": \"REJECTED\", \"reason\": \"Пустой список адресатов\"}")));

        assertThat(gateway(CALLBACK).send(message)).isEqualTo(new SendResult.Rejected("Пустой список адресатов"));
    }

    @Test
    void rejectionWithoutReasonNamesStatus() {
        server.stubFor(post("/v1/results").willReturn(aResponse().withStatus(404).withBody("Request was not matched")));

        assertThat(gateway(CALLBACK).send(message))
                .isEqualTo(new SendResult.Rejected("Result Gateway отклонил сообщение: HTTP 404"));
    }

    @Test
    void serverErrorIsFailure() {
        server.stubFor(post("/v1/results").willReturn(aResponse().withStatus(503)));

        assertThat(gateway(CALLBACK).send(message)).isEqualTo(new SendResult.Failed("Result Gateway ответил HTTP 503"));
    }

    @Test
    void slowGatewayIsFailure() {
        server.stubFor(post("/v1/results").willReturn(aResponse().withStatus(202).withFixedDelay(1500)));
        HttpResultGateway gateway = new HttpResultGateway(
                properties(server.baseUrl(), CALLBACK, Duration.ofMillis(300)), json, new MockEnvironment());

        assertThat(gateway.send(message)).isEqualTo(new SendResult.Failed("Result Gateway не ответил за 300 мс"));
    }

    @Test
    void unreachableGatewayIsFailure() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        String url = "http://localhost:" + closedPort;
        HttpResultGateway gateway = new HttpResultGateway(
                properties(url, CALLBACK, Duration.ofSeconds(2)), json, new MockEnvironment());

        assertThat(gateway.send(message)).isEqualTo(new SendResult.Failed("Result Gateway недоступен: " + url));
    }

    private HttpResultGateway gateway(URI callback) {
        return new HttpResultGateway(properties(server.baseUrl(), callback, Duration.ofSeconds(2)), json,
                new MockEnvironment());
    }

    private static ResultGatewayProperties properties(String url, URI callback, Duration readTimeout) {
        return new ResultGatewayProperties(URI.create(url), callback, Duration.ofSeconds(1), readTimeout);
    }
}
