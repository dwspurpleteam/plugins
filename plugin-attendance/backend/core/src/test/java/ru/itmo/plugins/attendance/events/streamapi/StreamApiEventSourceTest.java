package ru.itmo.plugins.attendance.events.streamapi;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.google.protobuf.Timestamp;

import io.grpc.ForwardingServerCall;
import io.grpc.ForwardingServerCallListener;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import ru.itmo.plugins.attendance.events.CallEvent;
import ru.itmo.plugins.attendance.events.EventSource;
import ru.itmo.plugins.attendance.streamapi.v1.EventServiceGrpc;
import ru.itmo.plugins.attendance.streamapi.v1.SubscribeEventsRequest;
import ru.itmo.plugins.attendance.streamapi.v1.SubscribeEventsResponse;

class StreamApiEventSourceTest {

    private static final Metadata.Key<String> COOKIE = Metadata.Key.of("cookie", Metadata.ASCII_STRING_MARSHALLER);
    private static final Instant JOINED_AT = Instant.parse("2026-10-22T10:00:05Z");

    private final FakeEventService service = new FakeEventService();
    private final BlockingQueue<Metadata> requestHeaders = new LinkedBlockingQueue<>();
    private final RecordingListener listener = new RecordingListener();
    private final StreamApiProperties properties = new StreamApiProperties(
            "in-process", true, Map.of("cookie", "composer_stage=secret:stage"),
            Duration.ofMinutes(6), Duration.ofSeconds(20), Duration.ofMillis(20), Duration.ofMillis(100));

    private Server server;
    private StreamApiEventSource source;

    @BeforeEach
    void startServer() throws IOException {
        String name = InProcessServerBuilder.generateName();
        server = InProcessServerBuilder.forName(name)
                .addService(ServerInterceptors.intercept(service, new HeadersAfterSubscribe(requestHeaders)))
                .build()
                .start();
        ManagedChannel channel = InProcessChannelBuilder.forName(name).build();
        source = new StreamApiEventSource(channel, properties, Clock.systemUTC());
    }

    @AfterEach
    void stopServer() throws InterruptedException {
        source.destroy();
        server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }

    @Test
    void subscribesToCallAndDeliversKnownEvents() throws InterruptedException {
        source.subscribe("RM_1", listener);

        SubscribeEventsRequest request = service.nextRequest();
        assertThat(request.getCallId()).isEqualTo("RM_1");
        assertThat(request.getEventTypesList()).containsExactly("participant.joined", "participant.left", "call.ended");
        Metadata headers = requestHeaders.poll(5, TimeUnit.SECONDS);
        assertThat(headers).isNotNull();
        assertThat(headers.get(StreamApiEventSource.CALL_ID_HEADER)).isEqualTo("RM_1");
        assertThat(headers.get(COOKIE)).isEqualTo("composer_stage=secret:stage");
        assertThat(listener.next()).isEqualTo("connected");

        ServerCallStreamObserver<SubscribeEventsResponse> stream = service.nextStream();
        stream.onNext(response("e1", "participant.joined", "anna"));
        stream.onNext(response("e2", "track.published", "anna"));
        stream.onNext(response("e3", "participant.left", "anna"));

        assertThat(listener.next()).isEqualTo("PARTICIPANT_JOINED anna " + JOINED_AT);
        assertThat(listener.next()).isEqualTo("PARTICIPANT_LEFT anna " + JOINED_AT);
    }

    @Test
    void resubscribesAfterStreamFailure() throws InterruptedException {
        source.subscribe("RM_1", listener);
        service.nextRequest();
        assertThat(listener.next()).isEqualTo("connected");

        service.nextStream().onError(Status.UNAVAILABLE.withDescription("event source unavailable").asRuntimeException());

        assertThat(listener.next()).isEqualTo("disconnected UNAVAILABLE: event source unavailable");
        assertThat(service.nextRequest().getCallId()).isEqualTo("RM_1");
        assertThat(listener.next()).isEqualTo("connected");
    }

    @Test
    void stopsOnPermanentError() throws InterruptedException {
        source.subscribe("RM_1", listener);
        service.nextRequest();
        assertThat(listener.next()).isEqualTo("connected");

        service.nextStream().onError(Status.INVALID_ARGUMENT.withDescription("invalid event subscription").asRuntimeException());

        assertThat(listener.next()).isEqualTo("failed INVALID_ARGUMENT: invalid event subscription");
        assertThat(service.requests.poll(300, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test
    void closingSubscriptionCancelsStream() throws InterruptedException {
        EventSource.Subscription subscription = source.subscribe("RM_1", listener);
        service.nextRequest();
        assertThat(listener.next()).isEqualTo("connected");

        subscription.close();

        assertThat(service.cancelled.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(listener.calls.poll(200, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test
    void reconnectDelayGrowsUpToLimit() {
        assertThat(source.reconnectDelayMillis(0)).isBetween(16L, 24L);
        assertThat(source.reconnectDelayMillis(1)).isBetween(32L, 48L);
        assertThat(source.reconnectDelayMillis(10)).isBetween(80L, 100L);
        assertThat(source.reconnectDelayMillis(1_000)).isBetween(80L, 100L);
    }

    private static SubscribeEventsResponse response(String id, String type, String participantId) {
        return SubscribeEventsResponse.newBuilder()
                .setEventId(id)
                .setType(type)
                .setSource("livekit")
                .setCallId("RM_1")
                .setRoomName("team-call")
                .setParticipantId(participantId)
                .setOccurredAt(Timestamp.newBuilder().setSeconds(JOINED_AT.getEpochSecond()))
                .build();
    }

    private static final class FakeEventService extends EventServiceGrpc.EventServiceImplBase {
        private final BlockingQueue<SubscribeEventsRequest> requests = new LinkedBlockingQueue<>();
        private final BlockingQueue<ServerCallStreamObserver<SubscribeEventsResponse>> streams =
                new LinkedBlockingQueue<>();
        private final CountDownLatch cancelled = new CountDownLatch(1);

        @Override
        public void subscribe(SubscribeEventsRequest request, StreamObserver<SubscribeEventsResponse> observer) {
            ServerCallStreamObserver<SubscribeEventsResponse> stream =
                    (ServerCallStreamObserver<SubscribeEventsResponse>) observer;
            stream.setOnCancelHandler(cancelled::countDown);
            streams.add(stream);
            requests.add(request);
        }

        private SubscribeEventsRequest nextRequest() throws InterruptedException {
            SubscribeEventsRequest request = requests.poll(5, TimeUnit.SECONDS);
            assertThat(request).as("subscription request").isNotNull();
            return request;
        }

        private ServerCallStreamObserver<SubscribeEventsResponse> nextStream() throws InterruptedException {
            ServerCallStreamObserver<SubscribeEventsResponse> stream = streams.poll(5, TimeUnit.SECONDS);
            assertThat(stream).as("subscription stream").isNotNull();
            return stream;
        }
    }

    /**
     * Как streamapi: заголовки ответа уходят сразу после регистрации подписки, до первого события.
     * Запоминает заголовки запроса для проверок.
     */
    private static final class HeadersAfterSubscribe implements ServerInterceptor {
        private final BlockingQueue<Metadata> received;

        private HeadersAfterSubscribe(BlockingQueue<Metadata> received) {
            this.received = received;
        }

        @Override
        public <Q, R> ServerCall.Listener<Q> interceptCall(
                ServerCall<Q, R> call, Metadata headers, ServerCallHandler<Q, R> next) {
            received.add(headers);
            AtomicBoolean sent = new AtomicBoolean();
            ServerCall<Q, R> sendOnce = new ForwardingServerCall.SimpleForwardingServerCall<>(call) {
                @Override
                public void sendHeaders(Metadata responseHeaders) {
                    if (sent.compareAndSet(false, true)) {
                        super.sendHeaders(responseHeaders);
                    }
                }
            };
            return new ForwardingServerCallListener.SimpleForwardingServerCallListener<>(next.startCall(sendOnce, headers)) {
                @Override
                public void onHalfClose() {
                    super.onHalfClose();
                    sendOnce.sendHeaders(new Metadata());
                }
            };
        }
    }

    private static final class RecordingListener implements EventSource.Listener {
        private final BlockingQueue<String> calls = new LinkedBlockingQueue<>();

        @Override
        public void onConnected() {
            calls.add("connected");
        }

        @Override
        public void onEvent(CallEvent event) {
            calls.add(event.type() + " " + event.participantId() + " " + event.occurredAt());
        }

        @Override
        public void onDisconnected(String reason) {
            calls.add("disconnected " + reason);
        }

        @Override
        public void onFailed(String reason) {
            calls.add("failed " + reason);
        }

        private String next() throws InterruptedException {
            String call = calls.poll(5, TimeUnit.SECONDS);
            assertThat(call).as("listener call").isNotNull();
            return call;
        }
    }
}
