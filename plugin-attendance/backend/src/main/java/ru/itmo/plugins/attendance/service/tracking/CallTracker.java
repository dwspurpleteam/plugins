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
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ru.itmo.plugins.attendance.service.activation.ActivationException.Reason;
import ru.itmo.plugins.attendance.service.activation.ActivationException;
import ru.itmo.plugins.attendance.service.events.CallEvent;
import ru.itmo.plugins.attendance.service.events.EventSource;

/**
 * Состав одного звонка, собранный из начального снимка и событий источника.
 *
 * <p>Порядок доставки не гарантирован, поэтому событие участника старше уже применённого
 * отбрасывается; повторы отсеиваются по идентификатору события.
 */
public final class CallTracker implements EventSource.Listener {

    private static final Logger log = LoggerFactory.getLogger(CallTracker.class);
    private static final int RECENT_EVENT_IDS = 4096;

    private final String callId;
    private final String source;
    private final Clock clock;
    private final List<String> ignoredPrefixes;
    private final List<String> initialParticipantIds;
    private final UUID sessionId = UUID.randomUUID();
    private final UUID activationId;
    private final Instant activatedAt;
    private final Consumer<AttendanceReport.Session> persistence;
    private final List<Visit> history = new ArrayList<>();

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
    private AttendanceReport.Session lastCheckpoint;
    private final CompletableFuture<Void> ready = new CompletableFuture<>();

    public CallTracker(String callId, String source, Clock clock, List<String> ignoredPrefixes) {
        this(callId, source, clock, ignoredPrefixes, List.of());
    }

    CallTracker(
            String callId,
            String source,
            Clock clock,
            List<String> ignoredPrefixes,
            List<String> initialParticipantIds
    ) {
        this(callId, source, clock, ignoredPrefixes, initialParticipantIds, null, ignored -> { });
    }

    CallTracker(String callId, String source, Clock clock, List<String> ignoredPrefixes,
            List<String> initialParticipantIds, UUID activationId, Consumer<AttendanceReport.Session> persistence) {
        this.callId = callId;
        this.source = source;
        this.clock = clock;
        this.ignoredPrefixes = List.copyOf(ignoredPrefixes);
        this.initialParticipantIds = List.copyOf(initialParticipantIds);
        this.activationId = activationId;
        this.activatedAt = clock.instant();
        this.persistence = persistence;
    }

    public String callId() {
        return callId;
    }

    @Override
    public void onConnected() {
        synchronized (this) {
            if (isFinished()) {
                return;
            }
            Instant now = clock.instant();
            if (trackingSince == null) {
                trackingSince = now;
                for (String participantId : initialParticipantIds) {
                    if (!isIgnored(participantId)) {
                        // У стартового участника ещё нет события, по которому можно проверять порядок доставки.
                        if (!visits.containsKey(participantId)) {
                            var visit = new Visit(participantId, now, null, Instant.MIN);
                            visit.presentAtStart = true;
                            addVisit(visit);
                        }
                    }
                }
                log.debug("Call {} started tracking: knownParticipantCount={}", callId, visits.size());
            }
            closeGap(now);
            status = CallStatus.TRACKING;
            statusMessage = null;
        }
        checkpoint();
        ready.complete(null);
    }

    @Override
    public void onDisconnected(String reason) {
        synchronized (this) {
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
        checkpoint();
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
        try {
            checkpoint();
        } finally {
            closeQuietly(finished);
        }
    }

    @Override
    public void onEvent(CallEvent event) {
        EventSource.Subscription finished = null;
        try {
            synchronized (this) {
                if (isFinished() || !callId.equals(event.callId())) {
                    return;
                }
                if (!event.id().isEmpty() && !recentEventIds.add(event.id())) {
                    return;
                }
                log.debug("Processing attendance event {} for call {} (occurredAt={})",
                        event.type(), callId, event.occurredAt());
                switch (event.type()) {
                    case PARTICIPANT_JOINED -> join(event.participantId(), event.occurredAt());
                    case PARTICIPANT_LEFT -> leave(event.participantId(), event.occurredAt());
                    case CALL_ENDED -> {
                        endedAt = event.occurredAt();
                        status = CallStatus.ENDED;
                        ready.completeExceptionally(new ActivationException(Reason.STOPPED, "Call ended during activation"));
                        statusMessage = null;
                        // Кто ещё числится в звонке, покинул его вместе с завершением.
                        visits.values().stream().filter(Visit::present).forEach(visit -> {
                            visit.closeAt(endedAt);
                            visit.endReason = "call_ended";
                        });
                        finished = finish();
                        log.info("Call {} ended", callId);
                    }
                }
            }
            checkpoint();
        } finally {
            closeQuietly(finished);
        }
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
        return new CallSnapshot(callId, source, status, statusMessage, trackingSince, endedAt, finishedAt,
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
            if (finishedAt == null) {
                finishedAt = clock.instant();
                status = CallStatus.FAILED;
                statusMessage = "Отслеживание остановлено сервисом";
                closeGap(finishedAt);
            }
            ready.completeExceptionally(new ActivationException(Reason.STOPPED, "Activation stopped"));
            current = subscription;
            subscription = null;
        }
        try {
            checkpoint();
        } finally {
            closeQuietly(current);
        }
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
            addVisit(new Visit(participantId, at, null, at));
            return;
        }
        if (at.isBefore(visit.lastEventAt)) {
            return;
        }
        if (!visit.present()) {
            visit = new Visit(participantId, at, null, at);
            addVisit(visit);
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
            var unknownEntry = new Visit(participantId, null, at, at);
            unknownEntry.endReason = "participant_left";
            addVisit(unknownEntry);
            return;
        }
        if (at.isBefore(visit.lastEventAt)) {
            return;
        }
        if (visit.present()) {
            visit.closeAt(at);
            visit.endReason = "participant_left";
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
    }

    private void addVisit(Visit visit) {
        visits.put(visit.participantId, visit);
        history.add(visit);
    }

    public synchronized AttendanceReport.Session reportSession() {
        var allVisits = history.stream().map(visit -> new AttendanceReport.Visit(visit.participantId,
                visit.joinedAt, visit.leftAt, visit.presentAtStart, visit.endReason)).toList();
        var allGaps = new ArrayList<>(gaps);
        if (gapStartedAt != null) {
            allGaps.add(new CallSnapshot.Gap(gapStartedAt, null));
        }
        return new AttendanceReport.Session(sessionId, activationId, activatedAt, trackingSince, endedAt,
                finishedAt, finishedAt == null ? clock.instant() : finishedAt, status, statusMessage, allVisits, allGaps);
    }

    /** A durable checkpoint is also taken periodically, so recovery never extends visits across a restart. */
    void checkpoint() {
        EventSource.Subscription failed = null;
        RuntimeException failure = null;
        synchronized (this) {
            var snapshot = reportSession();
            if (snapshot.equals(lastCheckpoint)) {
                return;
            }
            try {
                persistence.accept(snapshot);
                lastCheckpoint = snapshot;
            } catch (RuntimeException exception) {
                if (finishedAt == null) {
                    status = CallStatus.FAILED;
                    statusMessage = "Не удалось сохранить историю посещаемости";
                    finishedAt = clock.instant();
                    closeGap(finishedAt);
                }
                ready.completeExceptionally(new ActivationException(Reason.FAILED,
                        "Не удалось сохранить историю посещаемости"));
                failed = subscription;
                subscription = null;
                failure = exception;
            }
        }
        // SDK close can invoke callbacks; never invoke it while holding the tracker monitor.
        closeQuietly(failed);
        if (failure != null) {
            throw failure;
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
        private boolean presentAtStart;
        private String endReason;

        private Visit(String participantId, Instant joinedAt, Instant leftAt, Instant lastEventAt) {
            this.participantId = participantId;
            this.joinedAt = joinedAt;
            this.leftAt = leftAt;
            this.lastEventAt = lastEventAt;
        }

        private boolean present() {
            return leftAt == null;
        }

        private void closeAt(Instant at) {
            // Время вебхука округлено до секунд и может оказаться раньше начала учёта.
            leftAt = joinedAt != null && at.isBefore(joinedAt) ? joinedAt : at;
        }
    }
}
