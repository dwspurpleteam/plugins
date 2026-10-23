package ru.itmo.plugins.attendance.gateway;

/**
 * Отчёт Result Gateway о доставке: тело {@code POST /platform/deliveries}.
 *
 * @param reason  причина отказа; {@code null} при доставке
 * @param message исходное сообщение плагина целиком
 */
public record Delivery<P>(Status status, String reason, ResultMessage<P> message) {

    public enum Status {
        DELIVERED,
        REJECTED
    }
}
