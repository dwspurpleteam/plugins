package ru.itmo.plugins.attendance.config;

import java.time.Duration;

/** Проверки настроек при старте: неверное значение останавливает запуск с понятной ошибкой. */
public final class PropertyChecks {

    private PropertyChecks() {
    }

    public static Duration positive(Duration value, String name) {
        if (value == null || value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException(name + " must be a positive duration");
        }
        return value;
    }

    public static Duration notNegative(Duration value, String name) {
        if (value == null || value.isNegative()) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
        return value;
    }
}
