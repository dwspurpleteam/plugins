package ru.itmo.plugins.attendance.service.streamapi;

import java.time.Clock;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import pluginsdk.EventFilter;
import pluginsdk.EventListener;
import pluginsdk.PluginClient;
import pluginsdk.StreamFailure;
import pluginsdk.SubscriptionEnd;

import ru.itmo.plugins.attendance.configuration.StreamApiProperties;
import ru.itmo.plugins.attendance.service.activation.ActivationCredentials;
import ru.itmo.plugins.attendance.service.events.CallEvent;
import ru.itmo.plugins.attendance.service.events.CallEventType;
import ru.itmo.plugins.attendance.service.events.EventSource;

/** Adapts SDK subscriptions to attendance tracking. No history is replayed after reconnects. */
@Component
@ConditionalOnProperty(prefix = "attendance", name = "source", havingValue = "streamapi", matchIfMissing = true)
public class StreamApiEventSource implements EventSource, DisposableBean {
    private static final Logger log = LoggerFactory.getLogger(StreamApiEventSource.class);
    private static final EventFilter ATTENDANCE_EVENTS = EventFilter.of(CallEventType.wireNames().toArray(String[]::new));
    private final Set<PluginClient> clients = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final StreamApiProperties properties;
    private final Clock clock;

    public StreamApiEventSource(StreamApiProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
        log.info("Event source: SDK {}", describe());
    }

    @Override
    public String name() {
        return "streamapi";
    }

    @Override
    public String describe() {
        String headers = properties.metadata().keySet().stream().sorted().collect(Collectors.joining(", "));
        return properties.target() + " (" + (properties.plaintext() ? "без TLS" : "TLS")
                + (headers.isEmpty() ? ", без доп. заголовков" : ", заголовки: " + headers) + ")";
    }

    @Override
    public Subscription subscribe(String callId, ActivationCredentials credentials, Listener listener) {
        var client = openClient(credentials);
        try {
            var subscription = client.subscribeEvents(callId, ATTENDANCE_EVENTS, listenerFor(callId, listener));
            subscription.completion().whenComplete((end, error) -> {
                closeClient(client);
                if (end == SubscriptionEnd.REMOTE_COMPLETED) {
                    listener.onFailed("REMOTE_COMPLETED: event stream closed by server");
                }
            });
            return () -> {
                subscription.close();
                closeClient(client);
            };
        } catch (RuntimeException exception) {
            closeClient(client);
            throw exception;
        }
    }

    private PluginClient openClient(ActivationCredentials credentials) {
        var builder = PluginClient.builder(properties.target())
                .activation(credentials.activationId(), credentials.secret());
        if (properties.plaintext()) {
            builder.plaintext();
        }
        properties.metadata().forEach(builder::metadata);
        var client = builder.build();
        clients.add(client);
        if (closed.get()) {
            closeClient(client);
            throw new IllegalStateException("Event source is closed");
        }
        return client;
    }

    private EventListener listenerFor(String callId, Listener listener) {
        return new EventListener() {
            @Override
            public void onConnected() {
                log.info("Subscribed to events of call {} via SDK", callId);
                listener.onConnected();
            }

            @Override
            public void onEvent(pluginsdk.CallEvent event) {
                CallEventType.fromWireName(event.type()).ifPresent(type -> listener.onEvent(new CallEvent(
                        event.id(), type, event.callId(), event.participantId(),
                        event.occurredAt() == null ? clock.instant() : event.occurredAt())));
            }

            @Override
            public void onDisconnected(StreamFailure failure) {
                listener.onDisconnected(reason(failure));
            }

            @Override
            public void onFailed(StreamFailure failure) {
                log.warn("SDK subscription of call {} failed: {}", callId, reason(failure));
                listener.onFailed(reason(failure));
            }
        };
    }

    private void closeClient(PluginClient client) {
        clients.remove(client);
        client.close();
    }

    private static String reason(StreamFailure failure) {
        return failure.code() + ": " + failure.getMessage();
    }

    @Override
    public void destroy() {
        closed.set(true);
        clients.forEach(PluginClient::close);
        clients.clear();
    }
}
