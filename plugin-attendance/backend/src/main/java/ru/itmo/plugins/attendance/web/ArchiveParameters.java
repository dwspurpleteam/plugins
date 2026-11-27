package ru.itmo.plugins.attendance.web;

import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.ZoneId;

import org.springframework.http.HttpStatus;

final class ArchiveParameters {
    private ArchiveParameters() { }

    static String participantId(String participantId) {
        String value = participantId == null ? "" : participantId.strip();
        if (value.isEmpty() || value.getBytes(StandardCharsets.UTF_8).length > 256
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Укажите participantId без управляющих символов, не длиннее 256 байт");
        }
        return value;
    }

    static ZoneId timezone(String timezone) {
        try {
            return ZoneId.of(timezone);
        } catch (DateTimeException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Неизвестный часовой пояс");
        }
    }

    static LocalDate date(String value) {
        if (value == null) return null;
        try {
            if (!value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) throw new DateTimeException("Invalid date format");
            return LocalDate.parse(value);
        } catch (DateTimeException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Дата должна быть в формате YYYY-MM-DD");
        }
    }
}
