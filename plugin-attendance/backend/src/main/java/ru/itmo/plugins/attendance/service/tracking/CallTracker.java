package ru.itmo.plugins.attendance.service.tracking;

import static java.util.Comparator.comparing;
import static java.util.Comparator.naturalOrder;
import static java.util.Comparator.nullsFirst;
import static java.util.Comparator.reverseOrder;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ru.itmo.plugins.attendance.service.activation.ActivationException.Reason;
import ru.itmo.plugins.attendance.service.activation.ActivationException;
import ru.itmo.plugins.attendance.service.events.CallEvent;
import ru.itmo.plugins.attendance.service.events.EventSource;

/**
 * Состав одного звонка, собранный из событий источника.
 *
 * <p>Видит только тех, кто вошёл после начала отслеживания: streamapi не отдаёт историю и текущий
 * состав. Порядок доставки не гарантирован, поэтому событие участника старше уже применённого
 * отбрасывается; повторы отсеиваются по идентификатору события.
 */
public final class CallTracker implements EventSource.Listener {

    private static final Logger log = LoggerFactory.getLogger(CallTracker.class);
    private static final int MAX_GAPS = 20;
    private static final int RECENT_EVENT_IDS = 4096;

    private final String callId;
    private final String source;
    private final Clock clock;
    private final List<String> ignoredPrefixes;

    private final Map<String, Visit> visits = new HashMap<>();
    private final Set<String> recentEventIds = Collections.newSetFromMap(new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
            return size() > RECENT_EVENT_IDS;
        }
    });
    private final Deque<CallSnapshot.Gap> gaps = new ArrayDeque<>();

    private CallStatus status = CallStatus.CONNECTING;
    private String statusMessage;
    private Instant trackingSince;
    private Instant gapStartedAt;
    private Instant endedAt;
    private Instant finishedAt;
    private EventSource.Subscription subscription;
    private boolean closed;
    private final CompletableFuture<Void> ready = new CompletableFuture<>();

    public CallTracker(String callId, String source, Clock clock, List<String> ignoredPrefixes) {
        this.callId = callId;
        this.source = source;
        this.clock = clock;
        this.ignoredPrefixes = List.copyOf(ignoredPrefixes);
    }

    public String callId() {
        return callId;
    }

    @Override
    public synchronized void onConnected() {
        if (isFinished()) {
            return;
        }
        Instant now = clock.instant();
        if (trackingSince == null) {
            trackingSince = now;
        }
        closeGap(now);
        status = CallStatus.TRACKING;
        statusMessage = null;
        ready.complete(null);
    }

    @Override
    public synchronized void onDisconnected(String reason) {
        if (isFinished()) {
            return;
        }
        if (trackingSince == null) {
            status = CallStatus.CONNECTING;
        } else {
            status = CallStatus.RECONNECTING;
            if (gapStartedAt == null) {
                gapStartedAt = clock.instant();
            }
        }
        statusMessage = reason;
    }

    @Override
    public void onFailed(String reason) {
        EventSource.Subscription finished;
        synchronized (this) {
            if (isFinished()) {
                return;
            }
            status = CallStatus.FAILED;
            statusMessage = reason;
            ready.completeExceptionally(new ActivationException(Reason.FAILED, reason));
            finished = finish();
        }
        closeQuietly(finished);
    }

    @Override
    public void onEvent(CallEvent event) {
        EventSource.Subscription finished = null;
        synchronized (this) {
            if (isFinished() || !callId.equals(event.callId())) {
                return;
            }
            if (!event.id().isEmpty() && !recentEventIds.add(event.id())) {
                return;
            }
            switch (event.type()) {
                case PARTICIPANT_JOINED -> join(event.participantId(), event.occurredAt());
                case PARTICIPANT_LEFT -> leave(event.participantId(), event.occurredAt());
                case CALL_ENDED -> {
                    endedAt = event.occurredAt();
                    status = CallStatus.ENDED;
                    ready.completeExceptionally(new ActivationException(Reason.STOPPED, "Call ended during activation"));
                    statusMessage = null;
                    // Кто ещё числится в звонке, покинул его вместе с завершением.
                    visits.values().stream().filter(Visit::present).forEach(visit -> visit.leftAt = endedAt);
                    finished = finish();
                    log.info("Call {} ended", callId);
                }
            }
        }
        closeQuietly(finished);
    }

    public synchronized CallSnapshot snapshot() {
        List<CallSnapshot.Participant> present = new ArrayList<>();
        List<CallSnapshot.Participant> left = new ArrayList<>();
        for (Visit visit : visits.values()) {
            var participant = new CallSnapshot.Participant(visit.participantId, visit.joinedAt, visit.leftAt);
            (visit.present() ? present : left).add(participant);
        }
        present.sort(comparing(CallSnapshot.Participant::joinedAt, nullsFirst(naturalOrder()))
                .thenComparing(CallSnapshot.Participant::participantId));
        left.sort(comparing(CallSnapshot.Participant::leftAt, reverseOrder())
                .thenComparing(CallSnapshot.Participant::participantId));

        List<CallSnapshot.Gap> gapList = new ArrayList<>(gaps);
        if (gapStartedAt != null) {
            gapList.add(new CallSnapshot.Gap(gapStartedAt, null));
        }
        return new CallSnapshot(callId, source, status, statusMessage, trackingSince, endedAt,
                List.copyOf(gapList), clock.instant(), List.copyOf(present), List.copyOf(left));
    }

    public CallSnapshot awaitConnected(Duration timeout) {
        try {
            ready.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException exception) {
            var failure = new ActivationException(Reason.TIMEOUT, "Timed out waiting for the event subscription");
            if (failStartup(failure)) {
                throw failure;
            }
            // If a callback won the race, inspect its state instead of waiting again.
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            var failure = new ActivationException(Reason.STOPPED, "Activation request interrupted");
            failStartup(failure);
            throw failure;
        } catch (ExecutionException exception) {
            if (exception.getCause() instanceof ActivationException failure) {
                throw failure;
            }
            throw new ActivationException(Reason.FAILED, "Event subscription failed");
        }
        return connectedSnapshot();
    }

    private synchronized CallSnapshot connectedSnapshot() {
        if (closed || status != CallStatus.TRACKING) {
            throw new ActivationException(Reason.FAILED, "Activation is not currently connected");
        }
        return snapshot();
    }

    /** Completes readiness atomically so a concurrent successful connection wins over a timeout. */
    private boolean failStartup(ActivationException failure) {
        if (!ready.completeExceptionally(failure)) {
            return false;
        }
        onFailed(failure.getMessage());
        return true;
    }

    synchronized CallStatus status() {
        return status;
    }

    void attach(EventSource.Subscription newSubscription) {
        boolean closeNow;
        synchronized (this) {
            closeNow = isFinished();
            if (!closeNow) {
                subscription = newSubscription;
            }
        }
        if (closeNow) {
            closeQuietly(newSubscription);
        }
    }

    void close() {
        EventSource.Subscription current;
        synchronized (this) {
            closed = true;
            ready.completeExceptionally(new ActivationException(Reason.STOPPED, "Activation stopped"));
            current = subscription;
            subscription = null;
        }
        closeQuietly(current);
    }

    synchronized boolean shouldEvict(Instant now, Duration retention) {
        return closed || (finishedAt != null && Duration.between(finishedAt, now).compareTo(retention) > 0);
    }

    private void join(String participantId, Instant at) {
        if (isIgnored(participantId)) {
            return;
        }
        Visit visit = visits.get(participantId);
        if (visit == null) {
            visits.put(participantId, new Visit(participantId, at, null, at));
            return;
        }
        if (at.isBefore(visit.lastEventAt)) {
            return;
        }
        if (!visit.present()) {
            visit.joinedAt = at;
            visit.leftAt = null;
        }
        visit.lastEventAt = at;
    }

    private void leave(String participantId, Instant at) {
        if (isIgnored(participantId)) {
            return;
        }
        Visit visit = visits.get(participantId);
        if (visit == null) {
            // Вошёл до начала отслеживания: время входа неизвестно.
            visits.put(participantId, new Visit(participantId, null, at, at));
            return;
        }
        if (at.isBefore(visit.lastEventAt)) {
            return;
        }
        if (visit.present()) {
            visit.leftAt = at;
        }
        visit.lastEventAt = at;
    }

    private boolean isIgnored(String participantId) {
        return participantId.isBlank() || ignoredPrefixes.stream().anyMatch(participantId::startsWith);
    }

    private boolean isFinished() {
        return closed || status == CallStatus.ENDED || status == CallStatus.FAILED;
    }

    private EventSource.Subscription finish() {
        Instant now = clock.instant();
        finishedAt = now;
        closeGap(now);
        EventSource.Subscription current = subscription;
        subscription = null;
        return current;
    }

    private void closeGap(Instant now) {
        if (gapStartedAt == null) {
            return;
        }
        gaps.addLast(new CallSnapshot.Gap(gapStartedAt, now));
        gapStartedAt = null;
        while (gaps.size() > MAX_GAPS) {
            gaps.removeFirst();
        }
    }

    private void closeQuietly(EventSource.Subscription target) {
        if (target == null) {
            return;
        }
        try {
            target.close();
        } catch (RuntimeException e) {
            log.warn("Failed to close event subscription of call {}", callId, e);
        }
    }

    private static final class Visit {
        private final String participantId;
        private Instant joinedAt;
        private Instant leftAt;
        private Instant lastEventAt;

        private Visit(String participantId, Instant joinedAt, Instant leftAt, Instant lastEventAt) {
            this.participantId = participantId;
            this.joinedAt = joinedAt;
            this.leftAt = leftAt;
            this.lastEventAt = lastEventAt;
        }

        private boolean present() {
            return leftAt == null;
        }
    }
}
