package ru.itmo.plugins.attendance.tracking;

import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import ru.itmo.plugins.attendance.events.EventSource;

/**
 * Отслеживаемые звонки. Отслеживание начинается с первого запроса UI по callId и
 * останавливается, когда UI долго не спрашивает состав звонка.
 */
@Component
public class CallRegistry implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(CallRegistry.class);

    private final EventSource source;
    private final TrackingProperties properties;
    private final Clock clock;
    private final ConcurrentMap<String, CallTracker> trackers = new ConcurrentHashMap<>();

    public CallRegistry(EventSource source, TrackingProperties properties, Clock clock) {
        this.source = source;
        this.properties = properties;
        this.clock = clock;
    }

    /** Трекер звонка; при первом обращении подписывается на события. */
    public CallTracker getOrStart(String callId) {
        CallTracker tracker = trackers.get(callId);
        if (tracker == null) {
            tracker = start(callId);
        }
        tracker.touch(clock.instant());
        return tracker;
    }

    public int size() {
        return trackers.size();
    }

    @Scheduled(
            initialDelayString = "${attendance.tracking.cleanup-interval}",
            fixedDelayString = "${attendance.tracking.cleanup-interval}")
    public void evictStale() {
        Instant now = clock.instant();
        trackers.forEach((callId, tracker) -> {
            if (tracker.shouldEvict(now, properties.idleTimeout(), properties.failedRetention())
                    && trackers.remove(callId, tracker)) {
                log.info("Stopped tracking call {} ({})", callId, tracker.status());
                tracker.close();
            }
        });
    }

    @Override
    public void destroy() {
        trackers.values().forEach(CallTracker::close);
        trackers.clear();
    }

    private synchronized CallTracker start(String callId) {
        CallTracker existing = trackers.get(callId);
        if (existing != null) {
            return existing;
        }
        if (trackers.size() >= properties.maxCalls()) {
            throw new TooManyCallsException(properties.maxCalls());
        }
        CallTracker tracker = new CallTracker(callId, source.name(), clock, properties.ignoredParticipantPrefixes());
        trackers.put(callId, tracker);
        try {
            tracker.attach(source.subscribe(callId, tracker));
        } catch (RuntimeException e) {
            trackers.remove(callId, tracker);
            throw e;
        }
        log.info("Started tracking call {} via {}", callId, source.name());
        return tracker;
    }
}
