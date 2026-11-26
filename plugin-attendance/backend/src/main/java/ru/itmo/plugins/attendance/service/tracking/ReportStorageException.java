package ru.itmo.plugins.attendance.service.tracking;

/** History is temporarily unavailable; callers must not substitute an incomplete successful report. */
public class ReportStorageException extends RuntimeException {
    public ReportStorageException(String message, Throwable cause) {
        super(message, cause);
    }

    public ReportStorageException(String message) {
        super(message);
    }
}
