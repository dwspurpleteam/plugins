package ru.itmo.plugins.attendance.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import ru.itmo.plugins.attendance.service.report.AttendanceReportRenderer;
import ru.itmo.plugins.attendance.service.tracking.AttendanceArchive;
import ru.itmo.plugins.attendance.service.tracking.AttendanceReport;
import ru.itmo.plugins.attendance.service.tracking.AttendanceReportStore;
import ru.itmo.plugins.attendance.service.tracking.CallStatus;
import ru.itmo.plugins.attendance.service.tracking.ReportStorageException;

class ArchiveControllerTest {
    private final AttendanceReportStore store = mock(AttendanceReportStore.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new ArchiveController(store),
            new ReportController(store, new AttendanceReportRenderer())).setControllerAdvice(new ApiErrors()).build();

    @Test
    void listsTrimmedSubjectAndExplicitDateRangeWithoutCaching() throws Exception {
        when(store.archive("anna", 2, 5, LocalDate.parse("2026-11-01"), LocalDate.parse("2026-11-30"),
                ZoneId.of("Europe/Moscow"))).thenReturn(new AttendanceArchive("anna", Instant.parse("2026-11-27T10:00:00Z"),
                        2592000, 2, 5, 8, 2, List.of()));
        var response = mvc.perform(get("/api/archive").param("participantId", " anna ").param("page", "2")
                .param("limit", "5").param("from", "2026-11-01").param("to", "2026-11-30")
                .param("timezone", "Europe/Moscow")).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(response.getContentAsString()).contains("\"participantId\":\"anna\"", "\"total\":8");
    }

    @Test
    void rejectsMalformedParametersBeforeStorage() throws Exception {
        assertBadRequest("participantId", "");
        assertBadRequest("participantId", "x\u0000y");
        assertBadRequest("participantId", "я".repeat(129));
        assertBadRequest("page", "0");
        assertBadRequest("page", "2147483648");
        assertBadRequest("limit", "101");
        assertBadRequest("limit", "0");
        assertBadRequest("timezone", "invalid/zone");
        assertBadRequest("from", "2026-02-30");
        assertBadRequest("to", "2026-1-2");
        assertBadRequest("to", "");
        assertThat(mvc.perform(get("/api/archive").param("participantId", "anna")
                .param("from", "2026-11-28").param("to", "2026-11-27")).andReturn().getResponse().getStatus()).isEqualTo(400);
        assertThat(mvc.perform(get("/api/archive")).andReturn().getResponse().getStatus()).isEqualTo(400);
        verifyNoInteractions(store);
    }

    @Test
    void archiveDownloadRequiresExactMembershipAndKeepsLiveDownloadCompatible() throws Exception {
        when(store.report("room")).thenReturn(Optional.of(report("anna")));
        var allowed = mvc.perform(get("/api/archive/calls/room/report").param("participantId", " anna ")
                .param("format", "csv").param("timezone", "Europe/Moscow")).andReturn().getResponse();
        assertThat(allowed.getStatus()).isEqualTo(200);
        assertThat(allowed.getContentType()).isEqualTo("text/csv;charset=UTF-8");
        assertThat(allowed.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(mvc.perform(get("/api/archive/calls/room/report").param("participantId", "ann"))
                .andReturn().getResponse().getStatus()).isEqualTo(403);
        assertThat(mvc.perform(get("/api/calls/room/report")).andReturn().getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void archiveDownloadReturnsMissingAndStorageErrors() throws Exception {
        when(store.report("missing")).thenReturn(Optional.empty());
        when(store.report("broken")).thenThrow(new ReportStorageException("internal-storage-path"));
        assertThat(mvc.perform(get("/api/archive/calls/missing/report").param("participantId", "anna"))
                .andReturn().getResponse().getStatus()).isEqualTo(404);
        var response = mvc.perform(get("/api/archive/calls/broken/report").param("participantId", "anna"))
                .andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getContentAsString()).doesNotContain("internal-storage-path");
    }

    @Test
    void archiveDownloadValidatesSubjectAndFormatBeforeStorage() throws Exception {
        assertThat(mvc.perform(get("/api/archive/calls/room/report")).andReturn().getResponse().getStatus()).isEqualTo(400);
        assertThat(mvc.perform(get("/api/archive/calls/room/report").param("participantId", " "))
                .andReturn().getResponse().getStatus()).isEqualTo(400);
        assertThat(mvc.perform(get("/api/archive/calls/room/report").param("participantId", "anna").param("format", "html"))
                .andReturn().getResponse().getStatus()).isEqualTo(400);
        verifyNoInteractions(store);
    }

    private void assertBadRequest(String name, String value) throws Exception {
        var request = get("/api/archive");
        if (!name.equals("participantId")) request.param("participantId", "anna");
        assertThat(mvc.perform(request.param(name, value)).andReturn().getResponse().getStatus())
                .as("%s=%s", name, value).isEqualTo(400);
    }

    private AttendanceReport report(String participant) {
        Instant at = Instant.parse("2026-11-27T10:00:00Z");
        var session = new AttendanceReport.Session(UUID.randomUUID(), UUID.randomUUID(), at, at, at.plusSeconds(60),
                at.plusSeconds(60), at.plusSeconds(60), CallStatus.ENDED, null,
                List.of(new AttendanceReport.Visit(participant, at, at.plusSeconds(60), true, "call_ended")), List.of());
        return new AttendanceReport("room", at, List.of(session));
    }
}
