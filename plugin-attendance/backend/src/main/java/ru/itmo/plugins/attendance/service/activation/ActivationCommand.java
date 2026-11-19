package ru.itmo.plugins.attendance.service.activation;

import java.util.UUID;

import tools.jackson.databind.JsonNode;

/** Wire contract delivered by Plugin Manager. initialContext is accepted but not applied yet. */
public record ActivationCommand(String schemaVersion, UUID activationId, String pluginId,
        String pluginVersion, String registryId, String callId, String activationSecret, JsonNode initialContext) {
    public ActivationCredentials credentials() {
        return new ActivationCredentials(activationId, activationSecret);
    }

    @Override
    public String toString() {
        return "ActivationCommand[activationId=" + activationId + ", callId=" + callId + "]";
    }
}
