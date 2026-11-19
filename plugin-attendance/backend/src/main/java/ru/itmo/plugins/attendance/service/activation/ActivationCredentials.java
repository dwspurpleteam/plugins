package ru.itmo.plugins.attendance.service.activation;

import java.util.Objects;
import java.util.UUID;

public record ActivationCredentials(
        UUID activationId,
        String secret
) {
    public ActivationCredentials {
        Objects.requireNonNull(activationId, "activationId");
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("activation secret must not be blank");
        }
    }

    @Override
    public String toString() {
        return "ActivationCredentials[activationId=" + activationId + "]";
    }
}
