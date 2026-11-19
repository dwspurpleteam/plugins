package ru.itmo.plugins.attendance.service.activation;

public class ActivationException extends RuntimeException {
    public enum Reason {
        CONFLICT,
        FAILED,
        TIMEOUT,
        STOPPED
    }

    private final Reason reason;

    public ActivationException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
