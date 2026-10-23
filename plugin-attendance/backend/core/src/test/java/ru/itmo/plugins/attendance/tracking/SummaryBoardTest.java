package ru.itmo.plugins.attendance.tracking;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import ru.itmo.plugins.attendance.tracking.SummaryBoard.Publication;
import ru.itmo.plugins.attendance.tracking.SummaryBoard.Status;

class SummaryBoardTest {

    private static final Instant START = Instant.parse("2026-10-23T10:00:00Z");

    private final SummaryBoard board = new SummaryBoard();

    @Test
    void everyPublicationGetsNextRevisionAndNewMessageId() {
        Publication first = board.start(at(0));
        Publication second = board.start(at(1));

        assertThat(first.revision()).isEqualTo(1);
        assertThat(second.revision()).isEqualTo(2);
        assertThat(second.messageId()).isNotEqualTo(first.messageId());
        assertThat(board.view().publication()).isEqualTo(second);
        assertThat(second.status()).isEqualTo(Status.SENDING);
    }

    @Test
    void deliveryReplacesAcceptedStatus() {
        Publication started = board.start(at(0));
        board.sent(started, Status.ACCEPTED, null, at(1));

        assertThat(board.delivered(started.messageId(), 1, summary(3), 3, at(2))).isTrue();

        SummaryBoard.View view = board.view();
        assertThat(view.publication().status()).isEqualTo(Status.DELIVERED);
        assertThat(view.publication().updatedAt()).isEqualTo(at(2));
        assertThat(view.delivered()).isEqualTo(new SummaryBoard.Delivered(1, at(2), 3, summary(3)));
    }

    @Test
    void deliveryReportMayOutrunResponseToSend() {
        Publication started = board.start(at(0));
        board.delivered(started.messageId(), 1, summary(3), 3, at(1));

        Publication afterSend = board.sent(started, Status.ACCEPTED, null, at(2));

        assertThat(afterSend.status()).isEqualTo(Status.DELIVERED);
        assertThat(board.view().publication().status()).isEqualTo(Status.DELIVERED);
    }

    @Test
    void lateDeliveryOfFailedSendIsShown() {
        Publication started = board.start(at(0));
        board.sent(started, Status.FAILED, "timeout", at(5));

        board.delivered(started.messageId(), 1, summary(3), 3, at(6));

        assertThat(board.view().publication().status()).isEqualTo(Status.DELIVERED);
        assertThat(board.view().publication().reason()).isNull();
    }

    @Test
    void olderRevisionDoesNotReplaceDeliveredOne() {
        Publication first = board.start(at(0));
        Publication second = board.start(at(1));

        assertThat(board.delivered(second.messageId(), 2, summary(5), 5, at(2))).isTrue();
        assertThat(board.delivered(first.messageId(), 1, summary(3), 3, at(3))).isFalse();

        assertThat(board.view().delivered().revision()).isEqualTo(2);
        assertThat(board.view().publication()).extracting(Publication::revision, Publication::status)
                .containsExactly(2L, Status.DELIVERED);
    }

    @Test
    void responseToOutdatedPublicationDoesNotTouchCurrentOne() {
        Publication first = board.start(at(0));
        Publication second = board.start(at(1));

        Publication firstAfterSend = board.sent(first, Status.REJECTED, "no", at(2));

        assertThat(firstAfterSend.status()).isEqualTo(Status.REJECTED);
        assertThat(board.view().publication()).isEqualTo(second);
    }

    @Test
    void rejectionAfterAcceptIsFinal() {
        Publication started = board.start(at(0));
        board.sent(started, Status.ACCEPTED, null, at(1));

        board.rejected(started.messageId(), "Участник покинул звонок", at(2));

        assertThat(board.delivered(started.messageId(), 1, summary(3), 3, at(3))).isFalse();
        assertThat(board.view().publication())
                .extracting(Publication::status, Publication::reason)
                .containsExactly(Status.REJECTED, "Участник покинул звонок");
        assertThat(board.view().delivered()).isNull();
    }

    private static AttendanceSummary summary(int present) {
        return new AttendanceSummary(START, START, present, 0, "anna");
    }

    private static Instant at(int seconds) {
        return START.plusSeconds(seconds);
    }
}
