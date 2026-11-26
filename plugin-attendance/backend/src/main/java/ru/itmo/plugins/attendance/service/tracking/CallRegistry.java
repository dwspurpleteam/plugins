package ru.itmo.plugins.attendance.service.tracking;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import ru.itmo.plugins.attendance.configuration.TrackingProperties;
import ru.itmo.plugins.attendance.service.activation.ActivationCommand;
import ru.itmo.plugins.attendance.service.activation.ActivationException.Reason;
import ru.itmo.plugins.attendance.service.activation.ActivationException;
import ru.itmo.plugins.attendance.service.events.EventSource;

/**
 * Activation-owned trackers; the call map is an index for the existing read API.
 * A single attendance activation may own a call at a time.
 */
@Component
public class CallRegistry implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(CallRegistry.class);

    private final EventSource source;
    private final TrackingProperties properties;
    private final Clock clock;
    private final ConcurrentMap<String, CallTracker> trackers = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, Activation> activations = new ConcurrentHashMap<>();

    public CallRegistry(EventSource source, TrackingProperties properties, Clock clock) {
        this.source = source;
        this.properties = properties;
        this.clock = clock;
    }

    /** Снимок уже созданной активации; чтение не меняет её жизненный цикл. */
    public Optional<CallTracker> find(String callId) {
        return Optional.ofNullable(trackers.get(callId));
    }

    public int size() {
        return trackers.size();
    }

    public record Activation(ActivationCommand command, CallTracker tracker) {
        @Override
        public String toString() {
            return "Activation[activationId=" + command.activationId() + "]";
        }
    }

    /** Registration is serialized, but waiting for the connection must happen outside this lock. */
    public synchronized Activation activate(ActivationCommand command) {
        Activation previous = activations.get(command.activationId());
        if (previous != null) {
            if (!previous.command().equals(command)) {
                throw new ActivationException(Reason.CONFLICT, "Activation parameters do not match the original request");
            }
            // A manager retry can restart a failed launch after its resources have been closed.
            if (previous.tracker().status() != CallStatus.FAILED) {
                log.debug("Activation {} for call {} already has a tracker", command.activationId(), command.callId());
                return previous;
            }
            log.debug("Restarting failed activation {} for call {}", command.activationId(), command.callId());
            trackers.remove(command.callId(), previous.tracker());
            previous.tracker().close();
        }
        CallTracker existing = trackers.get(command.callId());
        if (existing != null) {
            if (existing.status() != CallStatus.FAILED && existing.status() != CallStatus.ENDED) {
                throw new ActivationException(Reason.CONFLICT, "Call already has an attendance activation");
            }
            trackers.remove(command.callId(), existing);
            existing.close();
        }
        if (trackers.size() >= properties.maxCalls()) {
            throw new TooManyCallsException(properties.maxCalls());
        }
        log.debug("Creating tracker for activation {} and call {}", command.activationId(), command.callId());
        var tracker = new CallTracker(command.callId(), source.name(), clock, properties.ignoredParticipantPrefixes());
        var activation = new Activation(command, tracker);
        activations.put(command.activationId(), activation);
        trackers.put(command.callId(), tracker);
        try {
            tracker.attach(source.subscribe(command.callId(), command.credentials(), tracker));
        } catch (RuntimeException exception) {
            log.debug("Could not start event subscription for activation {} and call {} ({})",
                    command.activationId(), command.callId(), exception.getClass().getSimpleName());
            tracker.onFailed("Could not start event subscription");
            throw new ActivationException(Reason.FAILED, "Could not start event subscription");
        }
        return activation;
    }

    @Scheduled(
            initialDelayString = "${attendance.tracking.cleanup-interval}",
            fixedDelayString = "${attendance.tracking.cleanup-interval}")
    public synchronized void evictStale() {
        Instant now = clock.instant();
        trackers.forEach((callId, tracker) -> {
            if (tracker.shouldEvict(now, properties.failedRetention())
                    && trackers.remove(callId, tracker)) {
                log.info("Stopped tracking call {} ({})", callId, tracker.status());
                tracker.close();
            }
        });
        activations.entrySet().removeIf(entry -> entry.getValue().tracker()
                .shouldEvict(now, properties.failedRetention()));
    }

    @Override
    public void destroy() {
        trackers.values().forEach(CallTracker::close);
        trackers.clear();
        activations.clear();
    }

}
