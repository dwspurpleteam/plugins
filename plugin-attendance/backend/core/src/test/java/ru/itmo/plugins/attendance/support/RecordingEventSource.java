package ru.itmo.plugins.attendance.support;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import ru.itmo.plugins.attendance.events.EventSource;

/** Источник, который только запоминает подписки; события тест отправляет слушателю сам. */
public final class RecordingEventSource implements EventSource {

    private final List<RecordedSubscription> subscriptions = new CopyOnWriteArrayList<>();

    public List<RecordedSubscription> subscriptions() {
        return subscriptions;
    }

    @Override
    public String name() {
        return "test";
    }

    @Override
    public String describe() {
        return "test";
    }

    @Override
    public Subscription subscribe(String callId, Listener listener) {
        RecordedSubscription subscription = new RecordedSubscription(callId, listener);
        subscriptions.add(subscription);
        return subscription;
    }

    public static final class RecordedSubscription implements Subscription {
        private final String callId;
        private final Listener listener;
        private volatile boolean closed;

        public RecordedSubscription(String callId, Listener listener) {
            this.callId = callId;
            this.listener = listener;
        }

        public String callId() {
            return callId;
        }

        public Listener listener() {
            return listener;
        }

        public boolean closed() {
            return closed;
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
