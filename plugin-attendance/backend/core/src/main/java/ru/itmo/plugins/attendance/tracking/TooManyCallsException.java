package ru.itmo.plugins.attendance.tracking;

public class TooManyCallsException extends RuntimeException {

    private final int limit;

    public TooManyCallsException(int limit) {
        super("Tracking limit of " + limit + " calls reached");
        this.limit = limit;
    }

    public int limit() {
        return limit;
    }
}
