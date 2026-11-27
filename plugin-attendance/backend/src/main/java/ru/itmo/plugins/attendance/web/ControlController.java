package ru.itmo.plugins.attendance.web;

import java.time.Duration;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import ru.itmo.plugins.attendance.service.activation.ActivationCommand;
import ru.itmo.plugins.attendance.service.activation.ActivationException;
import ru.itmo.plugins.attendance.service.activation.DeactivationCommand;
import ru.itmo.plugins.attendance.service.tracking.CallRegistry;
import ru.itmo.plugins.attendance.service.tracking.CallStatus;
import tools.jackson.databind.JsonNode;

@RestController
class ControlController {
    private static final Logger log = LoggerFactory.getLogger(ControlController.class);

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
    ControlResponse control(@RequestBody ControlCommand command) {
        if (command.operation() == null || "activate".equals(command.operation())) {
            return activate(new ActivationCommand(command.schemaVersion(), command.activationId(), command.pluginId(),
                    command.pluginVersion(), command.registryId(), command.callId(), command.activationSecret(),
                    command.initialContext()));
        }
        if (!"deactivate".equals(command.operation()) || !"1.0".equals(command.schemaVersion())
                || command.activationId() == null || !"attendance".equals(command.pluginId())
                || blank(command.requestedBy()) || blank(command.activationSecret())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid control message or unsupported operation/schema/plugin");
        }
        String callId = CallIds.requireValid(command.callId());
        if (!callId.equals(command.callId())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "callId must not have surrounding whitespace");
        }
        registry.deactivate(new DeactivationCommand(command.schemaVersion(), command.operation(), command.activationId(),
                callId, command.pluginId(), command.requestedBy(), command.activationSecret()));
        log.info("Activation {} for call {} deactivated by {}", command.activationId(), callId, command.requestedBy());
        return new ControlResponse(command.activationId(), callId, CallStatus.STOPPED);
    }

    private ControlResponse activate(ActivationCommand command) {
        long startedAt = System.nanoTime();
        if (!"1.0".equals(command.schemaVersion()) || command.activationId() == null
                || !"attendance".equals(command.pluginId()) || blank(command.pluginVersion())
                || blank(command.registryId()) || blank(command.activationSecret())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid activation message or unsupported schema/plugin");
        }
        String callId = CallIds.requireValid(command.callId());
        if (!callId.equals(command.callId())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "callId must not have surrounding whitespace");
        }
        log.debug("Activation {} for call {} received: pluginVersion={}, startTimeoutMs={}",
                command.activationId(), callId, command.pluginVersion(), startTimeout.toMillis());
        try {
            var activation = registry.activate(command);
            Duration remaining = startTimeout.minusNanos(System.nanoTime() - startedAt);
            var snapshot = activation.tracker().awaitConnected(remaining.isNegative() ? Duration.ZERO : remaining);
            log.info("Activation {} for call {} ready after {} ms", command.activationId(), callId,
                    Duration.ofNanos(System.nanoTime() - startedAt).toMillis());
            return new ControlResponse(command.activationId(), callId, snapshot.status());
        } catch (ActivationException exception) {
            log.warn("Activation {} for call {} ended with {} after {} ms: {}", command.activationId(), callId,
                    exception.reason(), Duration.ofNanos(System.nanoTime() - startedAt).toMillis(),
                    exception.getMessage());
            throw exception;
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    record ControlResponse(UUID activationId, String callId, CallStatus status) {
    }

    record ControlCommand(String operation, String schemaVersion, UUID activationId, String pluginId,
            String pluginVersion, String registryId, String callId, String activationSecret, JsonNode initialContext,
            String requestedBy) {
        @Override
        public String toString() {
            return "ControlCommand[operation=" + operation + ", activationId=" + activationId + ", callId=" + callId + "]";
        }
    }
}
