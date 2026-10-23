package ru.itmo.plugins.attendance.tracking;

import java.time.Instant;
import java.util.UUID;

/**
 * Публикации сводки одного звонка: статус последней публикации и последняя доставленная сводка.
 *
 * <p>Ответ Result Gateway на отправку и отчёт о доставке приходят независимо, отчёт может опередить ответ.
 * Поэтому ответ меняет только статус {@link Status#SENDING}, а отчёт — любой незавершённый. Доставки приходят
 * в любом порядке: более старая ревизия не заменяет уже доставленную.
 */
public final class SummaryBoard {

    private long lastRevision;
    private Publication publication;
    private Delivered delivered;

    /** Начинает публикацию: следующая ревизия и новый messageId. */
    public synchronized Publication start(Instant now) {
        publication = new Publication(UUID.randomUUID().toString(), ++lastRevision, Status.SENDING, null, now, now);
        return publication;
    }

    /** Ответ Result Gateway на отправку. Возвращает текущее состояние публикации. */
    public synchronized Publication sent(Publication started, Status status, String reason, Instant now) {
        if (!isCurrent(started.messageId())) {
            return started.with(status, reason, now);
        }
        if (publication.status() == Status.SENDING) {
            publication = publication.with(status, reason, now);
        }
        return publication;
    }

    /**
     * Отчёт о доставке.
     *
     * @return {@code false}, если уже доставлена та же или более новая ревизия либо сообщение отклонено
     */
    public synchronized boolean delivered(
            String messageId, long revision, AttendanceSummary summary, int recipients, Instant now) {
        if (isCurrent(messageId) && publication.status() == Status.REJECTED) {
            return false;
        }
        finish(messageId, Status.DELIVERED, null, now);
        if (delivered != null && delivered.revision() >= revision) {
            return false;
        }
        delivered = new Delivered(revision, now, recipients, summary);
        return true;
    }

    /** Отказ в доставке после приёма. */
    public synchronized void rejected(String messageId, String reason, Instant now) {
        finish(messageId, Status.REJECTED, reason, now);
    }

    public synchronized View view() {
        return new View(publication, delivered);
    }

    private void finish(String messageId, Status status, String reason, Instant now) {
        if (isCurrent(messageId) && !publication.status().finished()) {
            publication = publication.with(status, reason, now);
        }
    }

    private boolean isCurrent(String messageId) {
        return publication != null && publication.messageId().equals(messageId);
    }

    public enum Status {
        /** Отправляется в Result Gateway. */
        SENDING,
        /** Result Gateway принял сообщение, доставка ещё идёт. */
        ACCEPTED,
        DELIVERED,
        /** Result Gateway отклонил сообщение. */
        REJECTED,
        /** Result Gateway недоступен или ответил ошибкой. */
        FAILED;

        boolean finished() {
            return this == DELIVERED || this == REJECTED;
        }
    }

    /**
     * @param reason причина отказа или ошибки
     */
    public record Publication(
            String messageId,
            long revision,
            Status status,
            String reason,
            Instant requestedAt,
            Instant updatedAt) {

        Publication with(Status newStatus, String newReason, Instant now) {
            return new Publication(messageId, revision, newStatus, newReason, requestedAt, now);
        }
    }

    /**
     * @param recipients скольким участникам доставлена
     */
    public record Delivered(long revision, Instant deliveredAt, int recipients, AttendanceSummary summary) {
    }

    /**
     * @param publication последняя публикация, {@code null} — сводку ещё не публиковали
     * @param delivered   последняя доставленная сводка: её видят участники звонка
     */
    public record View(Publication publication, Delivered delivered) {
    }
}
