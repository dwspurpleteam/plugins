package ru.itmo.plugins.attendance.service.tracking;

import java.time.Instant;
import java.util.List;

/** A page of call summaries, without loading attendance intervals from disk. */
public record AttendanceArchive(String participantId, Instant serverTime, long retentionSeconds,
        int page, int limit, int total, int totalPages, List<Item> items) {
    public AttendanceArchive {
        items = List.copyOf(items);
    }

    public record Item(String callId, Instant trackingSince, Instant activatedAt, Instant endedAt,
            Instant stoppedAt, CallStatus status, int participantCount, boolean hasGaps,
            Instant expiresAt, boolean reportAvailable) {
        Instant startedAt() {
            return trackingSince == null ? activatedAt : trackingSince;
        }
    }
}
