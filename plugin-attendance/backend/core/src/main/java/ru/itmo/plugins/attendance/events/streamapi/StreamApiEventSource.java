package ru.itmo.plugins.attendance.events.streamapi;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.google.protobuf.Timestamp;

import io.grpc.CallOptions;
import io.grpc.ChannelCredentials;
import io.grpc.ClientCall;
import io.grpc.Grpc;
import io.grpc.InsecureChannelCredentials;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.TlsChannelCredentials;
import ru.itmo.plugins.attendance.events.CallEvent;
import ru.itmo.plugins.attendance.events.CallEventType;
import ru.itmo.plugins.attendance.events.EventSource;
import ru.itmo.plugins.attendance.streamapi.v1.EventServiceGrpc;
import ru.itmo.plugins.attendance.streamapi.v1.SubscribeEventsRequest;
import ru.itmo.plugins.attendance.streamapi.v1.SubscribeEventsResponse;

/**
 * События звонка из streamapi: {@code EventService.Subscribe} по gRPC.
 *
 * <p>streamapi отдаёт только новые события и не воспроизводит историю, поэтому после обрыва
 * подписка открывается заново, а события за перерыв теряются. Об установленной подписке говорят
 * заголовки ответа: streamapi отправляет их сразу после регистрации подписчика.
 */
@Component
@ConditionalOnProperty(prefix = "attendance", name = "source", havingValue = "streamapi", matchIfMissing = true)
public class StreamApiEventSource implements EventSource, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(StreamApiEventSource.class);

    /** По этому заголовку nginx стенда направляет подписки одного звонка на одну реплику. */
    static final Metadata.Key<String> CALL_ID_HEADER = Metadata.Key.of("x-call-id", Metadata.ASCII_STRING_MARSHALLER);

    /** Ошибки, которые не исправятся повтором того же запроса. */
    private static final Set<Status.Code> PERMANENT_ERRORS = EnumSet.of(
            Status.Code.INVALID_ARGUMENT,
            Status.Code.UNIMPLEMENTED,
            Status.Code.UNAUTHENTICATED,
            Status.Code.PERMISSION_DENIED);

    private final ManagedChannel channel;
    private final StreamApiProperties properties;
    private final Clock clock;
    private final Metadata extraHeaders;
    private final ScheduledExecutorService retries =
            Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("streamapi-retry").daemon().factory());
    private final Set<StreamSubscription> subscriptions = ConcurrentHashMap.newKeySet();

    @Autowired
    public StreamApiEventSource(StreamApiProperties properties, Clock clock) {
        this(createChannel(properties), properties, clock);
    }

    StreamApiEventSource(ManagedChannel channel, StreamApiProperties properties, Clock clock) {
        this.channel = channel;
        this.properties = properties;
        this.clock = clock;
        this.extraHeaders = toMetadata(properties.metadata());
    }

    static ManagedChannel createChannel(StreamApiProperties properties) {
        ChannelCredentials credentials = properties.plaintext()
                ? InsecureChannelCredentials.create()
                : TlsChannelCredentials.create();
        return Grpc.newChannelBuilder(properties.target(), credentials)
                .keepAliveTime(properties.keepaliveTime().toMillis(), TimeUnit.MILLISECONDS)
                .keepAliveTimeout(properties.keepaliveTimeout().toMillis(), TimeUnit.MILLISECONDS)
                .userAgent("plugin-attendance")
                .build();
    }

    @Override
    public String name() {
        return "streamapi";
    }

    @Override
    public String describe() {
        return properties.target() + (properties.plaintext() ? " (без TLS)" : " (TLS)");
    }

    @Override
    public Subscription subscribe(String callId, Listener listener) {
        StreamSubscription subscription = new StreamSubscription(callId, listener);
        subscriptions.add(subscription);
        subscription.connect();
        return subscription;
    }

    @Override
    public void destroy() throws InterruptedException {
        subscriptions.forEach(StreamSubscription::close);
        retries.shutdownNow();
        channel.shutdownNow();
        channel.awaitTermination(5, TimeUnit.SECONDS);
    }

    static Optional<CallEvent> toEvent(SubscribeEventsResponse message, Clock clock) {
        return CallEventType.fromWireName(message.getType()).map(type -> new CallEvent(
                message.getEventId(),
                type,
                message.getCallId(),
                message.getParticipantId(),
                message.hasOccurredAt() ? toInstant(message.getOccurredAt()) : clock.instant()));
    }

    static String reason(Status status) {
        StringBuilder text = new StringBuilder(status.getCode().name());
        if (status.getDescription() != null && !status.getDescription().isBlank()) {
            text.append(": ").append(status.getDescription());
        }
        if (status.getCause() != null && status.getCause().getMessage() != null) {
            text.append(" (").append(status.getCause().getMessage()).append(')');
        }
        return text.toString();
    }

    long reconnectDelayMillis(int failedAttempts) {
        long min = properties.reconnectMinDelay().toMillis();
        long max = properties.reconnectMaxDelay().toMillis();
        long base = Math.min(min << Math.min(failedAttempts, 16), max);
        double jitter = 0.8 + 0.4 * ThreadLocalRandom.current().nextDouble();
        return Math.max(1, Math.min(max, Math.round(base * jitter)));
    }

    private static Instant toInstant(Timestamp timestamp) {
        return Instant.ofEpochSecond(timestamp.getSeconds(), timestamp.getNanos());
    }

    private static Metadata toMetadata(Map<String, String> values) {
        Metadata metadata = new Metadata();
        values.forEach((name, value) -> {
            if (name.toLowerCase(Locale.ROOT).equals(CALL_ID_HEADER.name())) {
                throw new IllegalArgumentException("x-call-id is set by the plugin for every subscription");
            }
            metadata.put(Metadata.Key.of(name, Metadata.ASCII_STRING_MARSHALLER), value);
        });
        return metadata;
    }

    private final class StreamSubscription implements Subscription {
        private final String callId;
        private final Listener listener;
        private final Object lock = new Object();
        private boolean closed;
        private int failedAttempts;
        private ClientCall<SubscribeEventsRequest, SubscribeEventsResponse> call;
        private ScheduledFuture<?> retry;

        private StreamSubscription(String callId, Listener listener) {
            this.callId = callId;
            this.listener = listener;
        }

        private void connect() {
            Metadata headers = new Metadata();
            headers.merge(extraHeaders);
            headers.put(CALL_ID_HEADER, callId);
            SubscribeEventsRequest request = SubscribeEventsRequest.newBuilder()
                    .setCallId(callId)
                    .addAllEventTypes(CallEventType.wireNames())
                    .build();
            synchronized (lock) {
                if (closed) {
                    return;
                }
                retry = null;
                // Запуск под блокировкой: close() не должен отменить вызов между созданием и start().
                call = channel.newCall(EventServiceGrpc.getSubscribeMethod(), CallOptions.DEFAULT);
                call.start(new ResponseListener(call), headers);
                call.sendMessage(request);
                call.halfClose();
                call.request(1);
            }
        }

        @Override
        public void close() {
            ClientCall<SubscribeEventsRequest, SubscribeEventsResponse> current;
            ScheduledFuture<?> pending;
            synchronized (lock) {
                if (closed) {
                    return;
                }
                closed = true;
                current = call;
                pending = retry;
                call = null;
                retry = null;
            }
            subscriptions.remove(this);
            if (pending != null) {
                pending.cancel(false);
            }
            if (current != null) {
                current.cancel("Subscription closed", null);
            }
        }

        private boolean isCurrent(ClientCall<?, ?> candidate) {
            synchronized (lock) {
                return !closed && call == candidate;
            }
        }

        private void connected(ClientCall<?, ?> source) {
            synchronized (lock) {
                if (closed || call != source) {
                    return;
                }
                failedAttempts = 0;
            }
            log.info("Subscribed to events of call {}", callId);
            listener.onConnected();
        }

        private void closed(ClientCall<?, ?> source, Status status) {
            synchronized (lock) {
                if (closed || call != source) {
                    return;
                }
                call = null;
            }
            String reason = reason(status);
            if (PERMANENT_ERRORS.contains(status.getCode())) {
                log.error("streamapi rejected subscription of call {}: {}", callId, reason);
                subscriptions.remove(this);
                listener.onFailed(reason);
                return;
            }
            // Слушатель узнаёт об обрыве до того, как новая попытка сможет сообщить о подключении.
            listener.onDisconnected(reason);
            synchronized (lock) {
                if (closed) {
                    return;
                }
                long delay = reconnectDelayMillis(failedAttempts++);
                log.warn("Event stream of call {} interrupted: {}; reconnecting in {} ms", callId, reason, delay);
                try {
                    retry = retries.schedule(this::connect, delay, TimeUnit.MILLISECONDS);
                } catch (RejectedExecutionException e) {
                    log.debug("Retry of call {} skipped: source is shutting down", callId);
                }
            }
        }

        private final class ResponseListener extends ClientCall.Listener<SubscribeEventsResponse> {
            private final ClientCall<SubscribeEventsRequest, SubscribeEventsResponse> source;

            private ResponseListener(ClientCall<SubscribeEventsRequest, SubscribeEventsResponse> source) {
                this.source = source;
            }

            @Override
            public void onHeaders(Metadata headers) {
                connected(source);
            }

            @Override
            public void onMessage(SubscribeEventsResponse message) {
                if (!isCurrent(source)) {
                    return;
                }
                toEvent(message, clock).ifPresent(listener::onEvent);
                source.request(1);
            }

            @Override
            public void onClose(Status status, Metadata trailers) {
                closed(source, status);
            }
        }
    }
}
