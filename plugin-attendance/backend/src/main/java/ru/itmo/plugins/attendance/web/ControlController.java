package ru.itmo.plugins.attendance.web;

import java.time.Duration;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import ru.itmo.plugins.attendance.service.activation.ActivationCommand;
import ru.itmo.plugins.attendance.service.tracking.CallRegistry;
import ru.itmo.plugins.attendance.service.tracking.CallStatus;

@RestController
class ControlController {
    private final CallRegistry registry;
    private final Duration startTimeout;

    ControlController(CallRegistry registry,
            @Value("${attendance.activation.start-timeout:PT4S}") Duration startTimeout) {
        if (startTimeout.isNegative() || startTimeout.toMillis() < 1) {
            throw new IllegalArgumentException("attendance.activation.start-timeout must be at least 1ms");
        }
        this.registry = registry;
        this.startTimeout = startTimeout;
    }

    @PostMapping("/control")
    ControlResponse activate(@RequestBody ActivationCommand command) {
        if (!"1.0".equals(command.schemaVersion()) || command.activationId() == null
                || !"attendance".equals(command.pluginId()) || blank(command.pluginVersion())
                || blank(command.registryId()) || blank(command.activationSecret())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid activation message or unsupported schema/plugin");
        }
        String callId = CallIds.requireValid(command.callId());
        if (!callId.equals(command.callId())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "callId must not have surrounding whitespace");
        }
        var activation = registry.activate(command);
        var snapshot = activation.tracker().awaitConnected(startTimeout);
        return new ControlResponse(command.activationId(), callId, snapshot.status());
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    record ControlResponse(UUID activationId, String callId, CallStatus status) {
    }
}
