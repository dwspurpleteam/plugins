package ru.itmo.plugins.attendance.service.events;

import ru.itmo.plugins.attendance.service.activation.ActivationCredentials;

/**
 * Источник событий звонка.
 *
 * <p>Реализация сама переподключается после временных сбоев и сообщает о них слушателю.
 * Вызовы слушателя одной подписки идут последовательно и не пересекаются по времени.
 */
public interface EventSource {

    /** Короткое имя источника для API и UI. */
    String name();

    /** Куда подключён источник; показывается на странице разработчика. */
    String describe();

    /** Подписывается с данными активации. Результат подключения приходит слушателю. */
    Subscription subscribe(String callId, ActivationCredentials credentials, Listener listener);

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
