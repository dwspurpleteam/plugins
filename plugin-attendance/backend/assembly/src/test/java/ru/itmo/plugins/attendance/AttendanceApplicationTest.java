package ru.itmo.plugins.attendance;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.github.tomakehurst.wiremock.WireMockServer;

import ru.itmo.plugins.attendance.tracking.AttendanceSummary;
import ru.itmo.plugins.attendance.tracking.CallSnapshot;
import ru.itmo.plugins.attendance.tracking.CallSnapshot.Participant;
import ru.itmo.plugins.attendance.tracking.CallStatus;
import ru.itmo.plugins.attendance.tracking.SummaryBoard.Publication;
import ru.itmo.plugins.attendance.tracking.SummaryBoard.Status;
import tools.jackson.databind.json.JsonMapper;

/**
 * Приложение целиком с фейковым источником: страница, CORS для sandbox-iframe и API. Result Gateway —
 * WireMock с маппингами из {@code plugin-attendance/wiremock}, как у задачи {@code resultGatewayMock}.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "attendance.source=fake",
            "attendance.fake.connect-delay=0s",
            "attendance.fake.initial-participants=0",
            "attendance.fake.event-interval=0s"
        })
class AttendanceApplicationTest {

    private static final String ANNA = "anna-0123456789abcde";
    private static final WireMockServer gateway = startGateway();

    private final HttpClient http = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();

    @Value("${local.server.port}")
    private int port;

    @DynamicPropertySource
    static void resultGateway(DynamicPropertyRegistry registry) {
        registry.add("attendance.result-gateway.url", gateway::baseUrl);
    }

    @AfterAll
    static void stopGateway() {
        gateway.stop();
    }

    @Test
    void servesPluginPageAndAssetsToSandboxedIframe() throws Exception {
        HttpResponse<String> page = get("/?callId=demo", null);
        assertThat(page.statusCode()).isEqualTo(200);
        assertThat(page.body()).contains("<div id=\"root\"></div>");

        Matcher script = Pattern.compile("src=\"\\./(assets/[^\"]+\\.js)\"").matcher(page.body());
        assertThat(script.find()).as("module script in index.html").isTrue();
        // Модульный скрипт из iframe с sandbox="allow-scripts" запрашивается в режиме cors с Origin: null.
        HttpResponse<String> asset = get("/" + script.group(1), "null");
        assertThat(asset.statusCode()).isEqualTo(200);
        assertThat(asset.headers().firstValue("Access-Control-Allow-Origin")).hasValue("*");

        assertThat(get("/dev-host.html", null).statusCode()).isEqualTo(200);
    }

    @Test
    void tracksParticipantsOfCall() throws Exception {
        String participants = "/api/calls/RM_track/participants";
        CallSnapshot started = await(participants, snapshot -> snapshot.status() == CallStatus.TRACKING);
        assertThat(started.source()).isEqualTo("fake");
        assertThat(started.trackingSince()).isNotNull();

        assertThat(post("/dev/calls/RM_track/events", event("participant.joined")).statusCode()).isEqualTo(202);
        CallSnapshot joined = await(participants, snapshot -> !snapshot.present().isEmpty());
        assertThat(joined.present()).extracting(Participant::participantId).containsExactly(ANNA);

        assertThat(post("/dev/calls/RM_track/events", event("participant.left")).statusCode()).isEqualTo(202);
        CallSnapshot left = await(participants, snapshot -> !snapshot.left().isEmpty());
        assertThat(left.present()).isEmpty();
        assertThat(left.left()).extracting(Participant::participantId).containsExactly(ANNA);
    }

    @Test
    void participantsAreReadableFromSandboxedIframe() throws Exception {
        HttpResponse<String> response = get("/api/calls/RM_cors/participants", "null");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).hasValue("*");
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
                type -> assertThat(type).startsWith("application/json"));
        // Время уходит строкой ISO-8601, а не числом.
        assertThat(response.body()).containsPattern("\"serverTime\":\"\\d{4}-\\d{2}-\\d{2}T");
    }

    @Test
    void rejectsBlankCallId() throws Exception {
        HttpResponse<String> response = get("/api/calls/%20/participants", "null");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
                type -> assertThat(type).startsWith("application/problem+json"));
        assertThat(response.body()).contains("Не указан callId");
    }

    @Test
    void devEventsRequireTrackedCall() throws Exception {
        HttpResponse<String> response = post("/dev/calls/RM_unknown/events", event("participant.joined"));

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body()).contains("не отслеживается");
    }

    @Test
    void publishesSummaryThroughResultGateway() throws Exception {
        String participants = "/api/calls/RM_summary/participants";
        await(participants, snapshot -> snapshot.status() == CallStatus.TRACKING);
        post("/dev/calls/RM_summary/events", event("participant.joined"));
        await(participants, snapshot -> !snapshot.present().isEmpty());

        // Кнопка в sandbox-iframe: POST без тела с Origin: null.
        HttpResponse<String> response = postWithoutBody("/api/calls/RM_summary/summary?participantId=" + ANNA, "null");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).hasValue("*");
        Publication publication = json.readValue(response.body(), Publication.class);
        assertThat(publication.status()).isEqualTo(Status.ACCEPTED);

        // Mock сообщает о доставке через 1–3 с.
        CallSnapshot delivered = await(participants, snapshot -> snapshot.summary().delivered() != null);
        assertThat(delivered.summary().publication().status()).isEqualTo(Status.DELIVERED);
        assertThat(delivered.summary().delivered().revision()).isEqualTo(publication.revision());
        assertThat(delivered.summary().delivered().recipients()).isEqualTo(1);
        assertThat(delivered.summary().delivered().summary())
                .extracting(AttendanceSummary::present, AttendanceSummary::publishedBy)
                .containsExactly(1, ANNA);
        gateway.verify(postRequestedFor(urlEqualTo("/v1/results"))
                .withRequestBody(matchingJsonPath("$.callId", equalTo("RM_summary")))
                .withRequestBody(matchingJsonPath("$.recipients[0]", equalTo(ANNA))));
    }

    @Test
    void resultGatewayRejectsSummaryWithoutRecipients() throws Exception {
        await("/api/calls/RM_nobody/participants", snapshot -> snapshot.status() == CallStatus.TRACKING);

        HttpResponse<String> response = postWithoutBody("/api/calls/RM_nobody/summary", null);

        assertThat(response.statusCode()).isEqualTo(200);
        Publication publication = json.readValue(response.body(), Publication.class);
        assertThat(publication.status()).isEqualTo(Status.REJECTED);
        assertThat(publication.reason()).contains("Пустой список адресатов");
    }

    @Test
    void infoNamesEventSource() throws Exception {
        HttpResponse<String> response = get("/api/info", null);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"source\":\"fake\"");
        assertThat(response.body()).contains("\"resultGateway\":\"" + gateway.baseUrl() + "/v1/results\"");
    }

    private CallSnapshot await(String path, Predicate<CallSnapshot> condition) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        CallSnapshot snapshot;
        do {
            snapshot = json.readValue(get(path, null).body(), CallSnapshot.class);
            if (condition.test(snapshot)) {
                return snapshot;
            }
            Thread.sleep(50);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("Condition not met, last snapshot: " + snapshot);
    }

    private static String event(String type) {
        return "{\"type\":\"" + type + "\",\"participantId\":\"" + ANNA + "\"}";
    }

    private HttpResponse<String> get(String path, String origin) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(path)).GET();
        if (origin != null) {
            request.header("Origin", origin);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String body) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(uri(path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postWithoutBody(String path, String origin) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(path)).POST(HttpRequest.BodyPublishers.noBody());
        if (origin != null) {
            request.header("Origin", origin);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static WireMockServer startGateway() {
        WireMockServer server = new WireMockServer(options()
                .dynamicPort()
                .usingFilesUnderDirectory(System.getProperty("wiremock.root", "../../wiremock")));
        server.start();
        return server;
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }
}
