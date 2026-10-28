package ru.itmo.plugins.attendance.events;

/**
 * Источник событий звонка.
 *
 * <p>Реализация сама переподключается после временных сбоев и сообщает о них слушателю.
 * Вызовы слушателя одной подписки идут последовательно и не пересекаются по времени.
 */
public interface EventSource {

    /** Короткое имя для API и UI: {@code streamapi} или {@code fake}. */
    String name();

    /** Куда подключён источник; показывается на странице разработчика. */
    String describe();

    /** Подписывается на события звонка. Не блокирует поток: результат приходит слушателю. */
    Subscription subscribe(String callId, Listener listener);

    interface Listener {

        /** Подписка активна: впервые или после переподключения. */
        void onConnected();

        void onEvent(CallEvent event);

        /** Связь потеряна; источник повторит подключение сам. */
        void onDisconnected(String reason);

        /** Подписка невозможна, повторов не будет. */
        void onFailed(String reason);
    }

    interface Subscription extends AutoCloseable {

        @Override
        void close();
    }
}
