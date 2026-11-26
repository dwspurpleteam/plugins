package ru.itmo.plugins.attendance;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.json.JsonMapper;

import ru.itmo.plugins.attendance.service.tracking.AttendanceReport;
import ru.itmo.plugins.attendance.service.tracking.AttendanceReportStore;
import ru.itmo.plugins.attendance.service.tracking.CallSnapshot;
import ru.itmo.plugins.attendance.service.tracking.CallSnapshot.Participant;
import ru.itmo.plugins.attendance.service.tracking.CallStatus;

/** Full application tests with a test-only event source. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "logging.level.ru.itmo.plugins.attendance=DEBUG",
            "attendance.source=fake",
            "attendance.streamapi.target=unused:19090",
            "attendance.fake.connect-delay=0s",
            "attendance.fake.initial-participants=0",
            "attendance.fake.event-interval=0s",
            "attendance.fake.max-participants=10"
        })
@ExtendWith(OutputCaptureExtension.class)
class PluginAttendanceApplicationTest {

    private static final String ANNA = "anna-0123456789abcde";
    private static final Path REPORTS_DIRECTORY = Path.of(System.getProperty("java.io.tmpdir"),
            "attendance-http-tests-" + UUID.randomUUID());

    @DynamicPropertySource
    static void reportStorage(DynamicPropertyRegistry properties) {
        properties.add("attendance.reports.directory", REPORTS_DIRECTORY::toString);
    }

    @Autowired
    private AttendanceReportStore reports;

    @Autowired
    private Clock clock;

    private final HttpClient http = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();

    @Value("${local.server.port}")
    private int port;

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

    }

    @Test
    void tracksParticipantsOfCall() throws Exception {
        activate("RM_track");
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
    void returnsInitialParticipantsAsSoonAsActivationCompletes() throws Exception {
        String body = """
                {"schemaVersion":"1.0","activationId":"%s","pluginId":"attendance",
                 "pluginVersion":"1.0.1","registryId":"local","activationSecret":"test-secret",
                 "callId":"RM_initial","initialContext":{"callId":"RM_initial","participants":[
                   {"participantIdentity":"%s","joinDate":1000},
                   {"participantIdentity":"boris","joinDate":2000}
                 ],"snapshot":{"capturedAt":"2026-11-26T10:00:00Z","source":"call-backend","consistency":"partial"}}}
                """.formatted(java.util.UUID.randomUUID(), ANNA);
        assertThat(post("/control", body).statusCode()).isEqualTo(200);

        String path = "/api/calls/RM_initial/participants";
        HttpResponse<String> response = get(path, "null");
        assertThat(response.statusCode()).isEqualTo(200);
        CallSnapshot started = json.readValue(response.body(), CallSnapshot.class);
        assertThat(started.status()).isEqualTo(CallStatus.TRACKING);
        assertThat(started.present()).extracting(Participant::participantId).containsExactly(ANNA, "boris");
        assertThat(started.present()).extracting(Participant::joinedAt).containsOnly(started.trackingSince());

        assertThat(post("/dev/calls/RM_initial/events", event("participant.left")).statusCode()).isEqualTo(202);
        CallSnapshot left = await(path, snapshot -> !snapshot.left().isEmpty());
        assertThat(left.present()).extracting(Participant::participantId).containsExactly("boris");
        assertThat(left.left()).extracting(Participant::participantId).containsExactly(ANNA);
    }

    @Test
    void logsHttpRequestsWithoutCredentialsOrQueryParameters(CapturedOutput output) throws Exception {
        assertThat(get("/actuator/health", null).statusCode()).isEqualTo(200);
        activate("RM_logging");
        assertThat(get("/api/calls/RM_logging/participants?probe=hidden-query-value", null).statusCode())
                .isEqualTo(200);

        assertThat(output.getAll())
                .containsPattern("HTTP #\\d+ started: POST /control")
                .containsPattern("HTTP #\\d+ completed: POST /control -> 200 in \\d+ ms")
                .contains("GET /actuator/health -> 200", "GET /api/calls/RM_logging/participants -> 200")
                .doesNotContain("test-secret", "hidden-query-value");
    }

    @Test
    void participantsAreReadableFromSandboxedIframe() throws Exception {
        activate("RM_cors");
        HttpResponse<String> response = get("/api/calls/RM_cors/participants", "null");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).hasValue("*");
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
                type -> assertThat(type).startsWith("application/json"));
        // Время уходит строкой ISO-8601, а не числом.
        assertThat(response.body()).containsPattern("\"serverTime\":\"\\d{4}-\\d{2}-\\d{2}T");
    }

    @Test
    void exportsEveryVisit() throws Exception {
        String callId = "RM_export_history";
        activate(callId);
        for (String type : new String[] { "participant.joined", "participant.left",
                "participant.joined", "participant.left", "call.ended" }) {
            assertThat(post("/dev/calls/" + callId + "/events", event(type)).statusCode()).isEqualTo(202);
        }
        await("/api/calls/" + callId + "/participants", snapshot -> snapshot.status() == CallStatus.ENDED);
        assertThat(reports.report(callId).orElseThrow().sessions().getFirst().visits()).hasSize(2);

        HttpResponse<String> csv = get("/api/calls/" + callId
                + "/report?format=csv&timezone=Europe%2FMoscow", "null");
        assertThat(csv.statusCode()).isEqualTo(200);
        assertThat(csv.headers().firstValue("Access-Control-Allow-Origin")).hasValue("*");
        assertThat(csv.headers().firstValue("Access-Control-Expose-Headers")).hasValue("Content-Disposition");
        assertThat(csv.headers().firstValue("Content-Type")).hasValueSatisfying(
                type -> assertThat(type).startsWith("text/csv"));
        assertThat(csv.headers().firstValue("Content-Disposition")).hasValueSatisfying(
                value -> assertThat(value).contains("attachment", ".csv"));
        assertThat(csv.body()).contains("Anna", "Europe/Moscow");

        HttpResponse<byte[]> pdf = http.send(HttpRequest.newBuilder(uri("/api/calls/" + callId
                + "/report?format=pdf&timezone=UTC")).header("Origin", "null").GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertThat(pdf.statusCode()).isEqualTo(200);
        assertThat(new String(pdf.body(), 0, 5, java.nio.charset.StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
    }

    @Test
    void downloadsArchivedReportWithoutAnInMemoryTracker() throws Exception {
        String callId = "RM_archived_export";
        saveArchivedReport(callId);
        assertThat(get("/api/calls/" + callId + "/participants", "null").statusCode()).isEqualTo(404);
        HttpResponse<String> response = get("/api/calls/" + callId + "/report?format=csv", "null");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("Anna", "2 мин");
    }

    @Test
    void reportsUnreadableArchiveAsServiceUnavailable() throws Exception {
        String callId = "RM_unreadable_archive";
        var session = saveArchivedReport(callId);
        Path archive = REPORTS_DIRECTORY.resolve(session.sessionId() + ".json");
        byte[] original = Files.readAllBytes(archive);
        try {
            Files.writeString(archive, "invalid-json");
            HttpResponse<String> response = get("/api/calls/" + callId + "/report?format=csv", "null");
            assertThat(response.statusCode()).isEqualTo(503);
            assertThat(response.body()).contains("историю посещаемости").doesNotContain(archive.toString());
        } finally {
            Files.write(archive, original);
        }
    }

    private AttendanceReport.Session saveArchivedReport(String callId) {
        Instant stoppedAt = clock.instant().minusSeconds(60);
        Instant startedAt = stoppedAt.minusSeconds(120);
        var session = new AttendanceReport.Session(UUID.randomUUID(), UUID.randomUUID(), startedAt, startedAt,
                stoppedAt, stoppedAt, stoppedAt, CallStatus.ENDED, null,
                List.of(new AttendanceReport.Visit(ANNA, startedAt, stoppedAt, false, "call_ended")), List.of());
        reports.save(callId, session);
        return session;
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
    void infoNamesEventSource() throws Exception {
        HttpResponse<String> response = get("/api/info", null);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"source\":\"fake\"");
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

    private void activate(String callId) throws Exception {
        String body = "{\"schemaVersion\":\"1.0\",\"activationId\":\"" + java.util.UUID.randomUUID()
                + "\",\"pluginId\":\"attendance\",\"pluginVersion\":\"0.1.0\",\"registryId\":\"local\","
                + "\"activationSecret\":\"test-secret\",\"callId\":\"" + callId + "\"}";
        assertThat(post("/control", body).statusCode()).isEqualTo(200);
    }

    @Test
    void readingParticipantsDoesNotActivateCall() throws Exception {
        assertThat(get("/api/calls/RM_read_only/participants", null).statusCode()).isEqualTo(404);
        assertThat(post("/dev/calls/RM_read_only/events", event("participant.joined")).statusCode()).isEqualTo(404);
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

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }
}
