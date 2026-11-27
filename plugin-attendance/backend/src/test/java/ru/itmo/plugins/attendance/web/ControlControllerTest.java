package ru.itmo.plugins.attendance.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import ru.itmo.plugins.attendance.configuration.TrackingProperties;
import ru.itmo.plugins.attendance.service.activation.ActivationCredentials;
import ru.itmo.plugins.attendance.service.events.CallEvent;
import ru.itmo.plugins.attendance.service.events.CallEventType;
import ru.itmo.plugins.attendance.service.events.EventSource;
import ru.itmo.plugins.attendance.service.tracking.CallRegistry;

class ControlControllerTest {
    private final ControlledSource source = new ControlledSource();
    private final CallRegistry registry = new CallRegistry(source,
            new TrackingProperties(10, Duration.ofMinutes(1), Duration.ofSeconds(30), List.of("streamapi-")),
            Clock.systemUTC());
    private final UUID activationId = UUID.randomUUID();

    @AfterEach
    void close() {
        registry.destroy();
    }

    private MockMvc mvc(Duration timeout) {
        return MockMvcBuilders.standaloneSetup(new ControlController(registry, timeout))
                .setControllerAdvice(new ApiErrors()).build();
    }

    private String body(UUID id, String call) {
        return body(id, call, "null");
    }

    private String body(UUID id, String call, String initialContext) {
        return """
                {"schemaVersion":"1.0","activationId":"%s","pluginId":"attendance",
                 "pluginVersion":"0.1.0","registryId":"local","callId":"%s",
                 "activationSecret":"private-secret",
                 "initialContext":%s}
                """.formatted(id, call, initialContext);
    }

    private Callable<MvcResult> request(MockMvc mvc, String body) {
        return () -> mvc.perform(post("/control").contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
    }

    @Test
    void waitsForConnectionAndDeduplicatesConcurrentDeliveries() throws Exception {
        var mvc = mvc(Duration.ofSeconds(4));
        String body = body(activationId, "RM_1", """
                {"callId":"RM_1","participants":[
                  {"participantIdentity":"anna","joinDate":1000},
                  {"participantIdentity":"anna"},
                  {"participantIdentity":"streamapi-helper"},
                  {"participantIdentity":" "},
                  {"participantIdentity":123}]}
                """);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(request(mvc, body));
            var subscription = source.next();
            var second = executor.submit(request(mvc, body));
            assertThat(first.isDone()).isFalse();
            subscription.listener().onConnected();
            var response = first.get(5, TimeUnit.SECONDS).getResponse();
            assertThat(response.getStatus()).isEqualTo(200);
            assertThat(response.getContentAsString()).contains(activationId.toString(), "TRACKING")
                    .doesNotContain("private-secret");
            assertThat(second.get(5, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(200);
            assertThat(source.subscribed.get()).isEqualTo(1);
            assertThat(subscription.credentials().activationId()).isEqualTo(activationId);
            assertThat(subscription.credentials().secret()).isEqualTo("private-secret");
            var tracker = registry.find("RM_1").orElseThrow();
            var started = tracker.snapshot();
            assertThat(started.present()).hasSize(1);
            assertThat(started.present().getFirst().participantId()).isEqualTo("anna");
            assertThat(started.present().getFirst().joinedAt()).isEqualTo(started.trackingSince());

            var listener = subscription.listener();
            listener.onEvent(new CallEvent("leave", CallEventType.PARTICIPANT_LEFT,
                    "RM_1", "anna", started.trackingSince().minusMillis(100)));
            listener.onDisconnected("temporary outage");
            listener.onConnected();
            assertThat(request(mvc, body).call().getResponse().getStatus()).isEqualTo(200);
            assertThat(source.subscribed.get()).isEqualTo(1);
            var afterReconnect = tracker.snapshot();
            assertThat(afterReconnect.present()).isEmpty();
            assertThat(afterReconnect.left().getFirst().leftAt()).isEqualTo(started.trackingSince());
            assertThat(request(mvc, body(activationId, "RM_changed")).call().getResponse().getStatus()).isEqualTo(409);
            assertThat(request(mvc, body.replace("private-secret", "changed")).call().getResponse().getStatus())
                    .isEqualTo(409);
        }
    }

    @Test
    void failureClosesSubscriptionAndManagerRetryCanRestart() throws Exception {
        var mvc = mvc(Duration.ofSeconds(4));
        String body = body(activationId, "RM_1");
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(request(mvc, body));
            source.next().listener().onFailed("PERMISSION_DENIED: event is not allowed");
            var failure = first.get(5, TimeUnit.SECONDS).getResponse();
            assertThat(failure.getStatus()).isEqualTo(502);
            assertThat(failure.getContentAsString()).contains("PERMISSION_DENIED");
            assertThat(source.closed.get()).isEqualTo(1);

            var retry = executor.submit(request(mvc, body));
            source.next().listener().onConnected();
            assertThat(retry.get(5, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(200);
            assertThat(source.subscribed.get()).isEqualTo(2);
        }
    }

    @Test
    void timeoutClosesSubscriptionAndLateCallbackCannotReviveIt() throws Exception {
        var mvc = mvc(Duration.ofMillis(50));
        var response = request(mvc, body(activationId, "RM_timeout")).call().getResponse();
        var subscription = source.next();
        assertThat(response.getStatus()).isEqualTo(504);
        assertThat(response.getContentAsString()).contains("ACTIVATION_TIMEOUT");
        assertThat(source.closed.get()).isEqualTo(1);
        subscription.listener().onConnected();
        assertThat(registry.find("RM_timeout").orElseThrow().snapshot().status().name()).isEqualTo("FAILED");
    }

    @Test
    void isolatesCallsRejectsSecondOwnerAndClosesOnCallEnd() throws Exception {
        var mvc = mvc(Duration.ofSeconds(4));
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(request(mvc, body(activationId, "RM_1")));
            var one = source.next();
            one.listener().onConnected();
            assertThat(first.get(5, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(200);
            assertThat(request(mvc, body(UUID.randomUUID(), "RM_1")).call().getResponse().getStatus()).isEqualTo(409);

            var second = executor.submit(request(mvc, body(UUID.randomUUID(), "RM_2")));
            source.next().listener().onConnected();
            assertThat(second.get(5, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(200);
            one.listener().onEvent(new CallEvent("join", CallEventType.PARTICIPANT_JOINED, "RM_1", "anna", Instant.now()));
            assertThat(registry.find("RM_1").orElseThrow().snapshot().present()).hasSize(1);
            assertThat(registry.find("RM_2").orElseThrow().snapshot().present()).isEmpty();
            one.listener().onEvent(new CallEvent("end", CallEventType.CALL_ENDED, "RM_1", "", Instant.now()));
            assertThat(source.closed.get()).isEqualTo(1);
            assertThat(request(mvc, body(activationId, "RM_1")).call().getResponse().getStatus()).isEqualTo(502);
            registry.destroy();
            assertThat(source.closed.get()).isEqualTo(2);
        }
    }

    @Test
    void rejectsInvalidContractBeforeSubscribing() throws Exception {
        var mvc = mvc(Duration.ofSeconds(1));
        String valid = body(activationId, "RM_1");
        for (String invalid : List.of(valid.replace("\"1.0\"", "\"9.0\""),
                valid.replace("attendance", "other-plugin"), valid.replace("private-secret", ""),
                valid.replace(activationId.toString(), "bad-uuid"), valid.replace("RM_1", " "))) {
            assertThat(request(mvc, invalid).call().getResponse().getStatus()).isEqualTo(400);
        }
        assertThat(source.subscribed.get()).isZero();
        assertThat(mvc.perform(post("/platform/control").contentType(MediaType.APPLICATION_JSON)
                .content(valid)).andReturn().getResponse().getStatus()).isEqualTo(404);
    }

    private static class ControlledSource implements EventSource {
        private final BlockingQueue<SubscriptionRequest> requests = new LinkedBlockingQueue<>();
        private final AtomicInteger subscribed = new AtomicInteger();
        private final AtomicInteger closed = new AtomicInteger();

        @Override public String name() { return "controlled"; }
        @Override public String describe() { return "controlled"; }
        @Override public Subscription subscribe(String callId, ActivationCredentials credentials, Listener listener) {
            subscribed.incrementAndGet();
            requests.add(new SubscriptionRequest(credentials, listener));
            var done = new java.util.concurrent.atomic.AtomicBoolean();
            return () -> { if (done.compareAndSet(false, true)) closed.incrementAndGet(); };
        }
        SubscriptionRequest next() throws InterruptedException {
            var request = requests.poll(5, TimeUnit.SECONDS);
            assertThat(request).isNotNull();
            return request;
        }
    }

    private record SubscriptionRequest(ActivationCredentials credentials, EventSource.Listener listener) { }
}
