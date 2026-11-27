package ru.itmo.plugins.attendance.service.report;

/** A report could not be rendered; the cause is intended for server logs, not the HTTP response. */
public final class ReportGenerationException extends RuntimeException {
    public ReportGenerationException(String message, Throwable cause) {
        super(message, cause);
    }
}
