package ru.itmo.plugins.attendance.service.support;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import ru.itmo.plugins.attendance.service.activation.ActivationCommand;
import ru.itmo.plugins.attendance.service.activation.ActivationCredentials;
import ru.itmo.plugins.attendance.service.tracking.CallRegistry;
import ru.itmo.plugins.attendance.service.tracking.CallTracker;

public final class TestActivations {
    private TestActivations() {
    }

    public static ActivationCommand command(String callId) {
        UUID id = UUID.nameUUIDFromBytes(callId.getBytes(StandardCharsets.UTF_8));
        return new ActivationCommand("1.0", id, "attendance", "0.1.0", "test", callId, "test-secret", null);
    }

    public static ActivationCredentials credentials() {
        return command("test-call").credentials();
    }

    public static CallTracker start(CallRegistry registry, String callId) {
        return registry.activate(command(callId)).tracker();
    }
}
