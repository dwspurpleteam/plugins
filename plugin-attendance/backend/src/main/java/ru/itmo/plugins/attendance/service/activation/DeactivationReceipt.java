package ru.itmo.plugins.attendance.service.activation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/** Durable fence against delayed activation deliveries; never stores the bearer secret. */
public record DeactivationReceipt(UUID activationId, String callId, String credentialHash) {
    public static DeactivationReceipt from(DeactivationCommand command) {
        return new DeactivationReceipt(command.activationId(), command.callId(), hash(command.activationSecret()));
    }

    public boolean matches(String call, String secret) {
        return callId.equals(call) && MessageDigest.isEqual(credentialHash.getBytes(StandardCharsets.UTF_8),
                hash(secret).getBytes(StandardCharsets.UTF_8));
    }

    private static String hash(String secret) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(secret.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
