package ru.itmo.plugins.attendance.service.activation;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import tools.jackson.databind.JsonNode;

/** Wire contract delivered by Plugin Manager. */
public record ActivationCommand(String schemaVersion, UUID activationId, String pluginId,
        String pluginVersion, String registryId, String callId, String activationSecret, JsonNode initialContext) {
    public ActivationCredentials credentials() {
        return new ActivationCredentials(activationId, activationSecret);
    }

    public List<String> initialParticipantIds() {
        if (initialContext == null) {
            return List.of();
        }
        JsonNode participants = initialContext.path("participants");
        if (!participants.isArray()) {
            return List.of();
        }
        var participantIds = new ArrayList<String>();
        for (JsonNode participant : participants) {
            JsonNode identity = participant.path("participantIdentity");
            if (identity.isString()) {
                participantIds.add(identity.asString());
            }
        }
        return List.copyOf(participantIds);
    }

    @Override
    public String toString() {
        return "ActivationCommand[activationId=" + activationId + ", callId=" + callId + "]";
    }
}
