package ru.itmo.plugins.attendance.api;

import org.springframework.http.HttpStatus;

/** Ошибка запроса; сообщение показывается пользователю в UI. */
class ApiException extends RuntimeException {

    private final HttpStatus status;

    ApiException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    HttpStatus status() {
        return status;
    }
}
