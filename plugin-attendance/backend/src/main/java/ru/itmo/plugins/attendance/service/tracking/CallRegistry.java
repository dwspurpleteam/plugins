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
import org.springframework.beans.factory.annotation.Autowired;
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
    private final AttendanceReportStore reports;
    private final ConcurrentMap<String, CallTracker> trackers = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, Activation> activations = new ConcurrentHashMap<>();

    public CallRegistry(EventSource source, TrackingProperties properties, Clock clock) {
        this(source, properties, clock, null);
    }

    @Autowired
    public CallRegistry(EventSource source, TrackingProperties properties, Clock clock, AttendanceReportStore reports) {
        this.source = source;
        this.properties = properties;
        this.clock = clock;
        this.reports = reports;
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
            previous.tracker().close();
            trackers.remove(command.callId(), previous.tracker());
        }
        CallTracker existing = trackers.get(command.callId());
        if (existing != null) {
            if (existing.status() != CallStatus.FAILED && existing.status() != CallStatus.ENDED) {
                throw new ActivationException(Reason.CONFLICT, "Call already has an attendance activation");
            }
            existing.close();
            trackers.remove(command.callId(), existing);
        }
        if (trackers.size() >= properties.maxCalls()) {
            throw new TooManyCallsException(properties.maxCalls());
        }
        var initialParticipantIds = command.initialParticipantIds();
        log.debug("Creating tracker for activation {} and call {}", command.activationId(), command.callId());
        var tracker = new CallTracker(command.callId(), source.name(), clock,
                properties.ignoredParticipantPrefixes(), initialParticipantIds, command.activationId(),
                session -> {
                    if (reports != null) {
                        reports.save(command.callId(), session);
                    }
                });
        tracker.checkpoint();
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
            try {
                tracker.checkpoint();
                if (tracker.shouldEvict(now, properties.failedRetention())) {
                    tracker.close();
                    trackers.remove(callId, tracker);
                    log.info("Stopped tracking call {} ({})", callId, tracker.status());
                }
            } catch (ReportStorageException exception) {
                log.error("Cannot checkpoint attendance history for call {}", callId, exception);
            }
        });
        // Failed final writes keep both indexes intact so activation retries can persist the old session.
        activations.entrySet().removeIf(entry -> trackers.get(entry.getValue().command().callId())
                != entry.getValue().tracker());
        if (reports != null) {
            reports.cleanup();
        }
    }

    @Override
    public void destroy() {
        trackers.forEach((callId, tracker) -> {
            try {
                tracker.close();
            } catch (ReportStorageException exception) {
                log.error("Cannot save attendance history when stopping call {}", callId, exception);
            }
        });
        trackers.clear();
        activations.clear();
    }

}
