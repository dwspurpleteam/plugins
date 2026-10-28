package ru.itmo.plugins.attendance.api;

import java.nio.charset.StandardCharsets;

import org.springframework.http.HttpStatus;

final class CallIds {

    /** Ограничение streamapi на идентификаторы. */
    static final int MAX_BYTES = 256;

    private CallIds() {
    }

    static String requireValid(String callId) {
        String value = callId == null ? "" : callId.strip();
        if (value.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Не указан callId");
        }
        if (value.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "callId длиннее " + MAX_BYTES + " байт");
        }
        if (value.chars().anyMatch(Character::isISOControl)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "callId содержит управляющие символы");
        }
        return value;
    }
}
