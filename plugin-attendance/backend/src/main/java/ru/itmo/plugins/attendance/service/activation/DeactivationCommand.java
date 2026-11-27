package ru.itmo.plugins.attendance.service.activation;

import java.util.UUID;

/** Stop command delivered by Plugin Manager to the same control endpoint as activation. */
public record DeactivationCommand(String schemaVersion, String operation, UUID activationId,
        String callId, String pluginId, String requestedBy, String activationSecret) {
    @Override
    public String toString() {
        return "DeactivationCommand[activationId=" + activationId + ", callId=" + callId + "]";
    }
}
