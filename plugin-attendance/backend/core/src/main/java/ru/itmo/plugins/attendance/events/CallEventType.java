package ru.itmo.plugins.attendance.events;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/** Типы событий, на которые подписывается плагин; имена совпадают с типами streamapi. */
public enum CallEventType {
    PARTICIPANT_JOINED("participant.joined"),
    PARTICIPANT_LEFT("participant.left"),
    CALL_ENDED("call.ended");

    private final String wireName;

    CallEventType(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    public static Optional<CallEventType> fromWireName(String value) {
        return Arrays.stream(values()).filter(type -> type.wireName.equals(value)).findFirst();
    }

    public static List<String> wireNames() {
        return Arrays.stream(values()).map(CallEventType::wireName).toList();
    }
}
