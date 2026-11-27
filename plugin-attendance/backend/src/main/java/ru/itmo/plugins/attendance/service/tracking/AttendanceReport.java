package ru.itmo.plugins.attendance.service.tracking;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Immutable attendance history. Each tracking attempt has its own session, including retries. */
public record AttendanceReport(String callId, Instant generatedAt, List<Session> sessions) {
    public AttendanceReport {
        sessions = List.copyOf(sessions);
    }

    public record Session(UUID sessionId, UUID activationId, Instant activatedAt, Instant trackingSince,
            Instant endedAt, Instant stoppedAt, Instant capturedAt, CallStatus status, String statusMessage,
            List<Visit> visits, List<CallSnapshot.Gap> gaps) {
        public Session {
            visits = List.copyOf(visits);
            gaps = List.copyOf(gaps);
        }
    }

    /** Null joinedAt means the actual entry was not observed; presentAtStart distinguishes seeded visits. */
    public record Visit(String participantId, Instant joinedAt, Instant leftAt,
            boolean presentAtStart, String endReason) {
    }
}
