package ru.itmo.plugins.attendance.events.fake;

import java.time.Clock;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import ru.itmo.plugins.attendance.events.CallEvent;
import ru.itmo.plugins.attendance.events.CallEventType;
import ru.itmo.plugins.attendance.events.EventSource;

/**
 * Источник для разработки без streamapi: имитирует входы и выходы участников и обрывы связи.
 *
 * <p>Ведёт себя как streamapi: события до подключения и во время обрыва теряются, а время
 * событий округляется до секунды. Состояние меняется только в потоке {@code fake-events}.
 */
@Component
@ConditionalOnProperty(prefix = "attendance", name = "source", havingValue = "fake")
public class FakeEventSource implements EventSource, DisposableBean {

    // "participant" Meet подставляет вместо имён без латиницы, например кириллических.
    private static final List<String> NAMES = List.of(
            "anna", "boris", "vera", "grigory", "daria", "egor", "zhanna", "ilya",
            "kira", "lev", "maria", "nikita", "olga", "pavel", "ivan_petrov", "participant");
    private static final String SUFFIX_ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyz";
    private static final int SUFFIX_LENGTH = 15;

    private final FakeProperties properties;
    private final Clock clock;
    private final ScheduledExecutorService executor =
            Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("fake-events").daemon().factory());
    private final Map<String, FakeCall> calls = new ConcurrentHashMap<>();
    private final Random random = new Random();

    public FakeEventSource(FakeProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public String name() {
        return "fake";
    }

    @Override
    public String describe() {
        return "генератор тестовых событий";
    }

    @Override
    public Subscription subscribe(String callId, Listener listener) {
        FakeCall call = new FakeCall(callId, listener);
        FakeCall previous = calls.put(callId, call);
        if (previous != null) {
            run(previous::stop);
        }
        run(call::start);
        return () -> {
            calls.remove(callId, call);
            run(call::stop);
        };
    }

    /**
     * Отправляет событие в подписку звонка. Без участника вход получает новый участник,
     * а выход — случайный из присутствующих.
     *
     * @return {@code false}, если звонок не отслеживается
     */
    public boolean emit(String callId, CallEventType type, String participantId) {
        FakeCall call = calls.get(callId);
        if (call == null) {
            return false;
        }
        run(() -> call.manual(type, participantId));
        return true;
    }

    /**
     * Имитирует обрыв связи с источником: события за это время теряются, как при сбое streamapi.
     *
     * @return {@code false}, если звонок не отслеживается
     */
    public boolean dropConnection(String callId, Duration duration) {
        FakeCall call = calls.get(callId);
        if (call == null) {
            return false;
        }
        run(() -> call.drop(duration));
        return true;
    }

    @Override
    public void destroy() {
        executor.shutdownNow();
    }

    private void run(Runnable action) {
        try {
            executor.execute(action);
        } catch (RejectedExecutionException e) {
            // Источник остановлен вместе с приложением.
        }
    }

    private String randomIdentity() {
        // Формат гостевого identity в Meet: <имя латиницей>-<15 символов [0-9a-z]>.
        StringBuilder identity = new StringBuilder(NAMES.get(random.nextInt(NAMES.size()))).append('-');
        for (int i = 0; i < SUFFIX_LENGTH; i++) {
            identity.append(SUFFIX_ALPHABET.charAt(random.nextInt(SUFFIX_ALPHABET.length())));
        }
        return identity.toString();
    }

    private final class FakeCall {
        private final String callId;
        private final Listener listener;
        private final Set<String> present = new LinkedHashSet<>();
        private final List<String> departed = new ArrayList<>();
        private final List<ScheduledFuture<?>> timers = new ArrayList<>();
        private boolean connected;
        private boolean stopped;

        private FakeCall(String callId, Listener listener) {
            this.callId = callId;
            this.listener = listener;
        }

        private void start() {
            after(properties.connectDelay(), () -> {
                connected = true;
                listener.onConnected();
                for (int i = 1; i <= properties.initialParticipants(); i++) {
                    after(Duration.ofMillis(600L * i), () -> join(randomIdentity()));
                }
                long interval = properties.eventInterval().toMillis();
                if (interval > 0) {
                    timers.add(executor.scheduleWithFixedDelay(
                            this::randomStep, interval, interval, TimeUnit.MILLISECONDS));
                }
            });
        }

        private void stop() {
            stopped = true;
            connected = false;
            timers.forEach(timer -> timer.cancel(false));
            timers.clear();
        }

        private void manual(CallEventType type, String participantId) {
            if (stopped) {
                return;
            }
            switch (type) {
                case PARTICIPANT_JOINED -> join(participantId != null ? participantId : randomIdentity());
                case PARTICIPANT_LEFT -> {
                    String target = participantId != null ? participantId : randomPresent();
                    if (target != null) {
                        leave(target);
                    }
                }
                case CALL_ENDED -> {
                    present.clear();
                    publish(CallEventType.CALL_ENDED, "");
                    stop();
                }
            }
        }

        private void drop(Duration duration) {
            if (stopped || !connected) {
                return;
            }
            connected = false;
            listener.onDisconnected("UNAVAILABLE: имитация обрыва связи");
            after(duration, () -> {
                connected = true;
                listener.onConnected();
            });
        }

        private void randomStep() {
            if (stopped) {
                return;
            }
            int roll = random.nextInt(100);
            boolean hasRoom = present.size() < properties.maxParticipants();
            if (present.isEmpty() || (hasRoom && (present.size() < 2 || roll < 45))) {
                join(randomIdentity());
            } else if (hasRoom && roll < 65 && !departed.isEmpty()) {
                join(departed.get(random.nextInt(departed.size())));
            } else {
                leave(randomPresent());
            }
        }

        private void join(String participantId) {
            present.add(participantId);
            departed.remove(participantId);
            publish(CallEventType.PARTICIPANT_JOINED, participantId);
        }

        private void leave(String participantId) {
            present.remove(participantId);
            if (!departed.contains(participantId)) {
                departed.add(participantId);
            }
            publish(CallEventType.PARTICIPANT_LEFT, participantId);
        }

        private String randomPresent() {
            if (present.isEmpty()) {
                return null;
            }
            return new ArrayList<>(present).get(random.nextInt(present.size()));
        }

        private void publish(CallEventType type, String participantId) {
            if (!connected || stopped) {
                return;
            }
            listener.onEvent(new CallEvent(UUID.randomUUID().toString(), type, callId, participantId,
                    clock.instant().truncatedTo(ChronoUnit.SECONDS)));
        }

        private void after(Duration delay, Runnable action) {
            timers.removeIf(Future::isDone);
            timers.add(executor.schedule(() -> {
                if (!stopped) {
                    action.run();
                }
            }, delay.toMillis(), TimeUnit.MILLISECONDS));
        }
    }
}
