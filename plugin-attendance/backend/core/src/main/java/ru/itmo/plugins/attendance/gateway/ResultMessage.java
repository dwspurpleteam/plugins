package ru.itmo.plugins.attendance.gateway;

import java.util.List;

/**
 * Результат backend плагина для Result Gateway в формате спецификации платформы
 * (specs: plugin-service-architecture, «Данные, результаты и команды»).
 *
 * <p>{@code activationId} пока не передаётся: контекст запуска плагина его не содержит.
 *
 * @param type       {@code call.result}: результат, который обновляется по ключу и ревизии
 * @param messageId  идентификатор сообщения; повтор с тем же messageId не исполняется второй раз
 * @param recipients участники звонка, которым адресован результат; пустой список Result Gateway отклоняет
 * @param key        ключ результата: новая ревизия заменяет предыдущую
 * @param revision   номер ревизии, растёт с каждой публикацией
 * @param payload    содержимое результата, его формат определяет плагин
 */
public record ResultMessage<P>(
        String type,
        String messageId,
        String callId,
        List<String> recipients,
        String key,
        long revision,
        P payload) {

    public static final String TYPE_RESULT = "call.result";
}
