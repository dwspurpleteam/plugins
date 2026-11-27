package ru.itmo.plugins.attendance.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import ru.itmo.plugins.attendance.service.report.AttendanceReportRenderer;
import ru.itmo.plugins.attendance.service.report.ReportGenerationException;
import ru.itmo.plugins.attendance.service.tracking.AttendanceReport;
import ru.itmo.plugins.attendance.service.tracking.AttendanceReportStore;

class ReportControllerTest {
    private final AttendanceReportStore store = mock(AttendanceReportStore.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new ReportController(store, new AttendanceReportRenderer()))
            .setControllerAdvice(new ApiErrors()).build();

    @Test
    void csvDownloadUsesBrowserTimezoneAndSafeFilename() throws Exception {
        when(store.report("RM_1")).thenReturn(Optional.of(new AttendanceReport("RM_1",
                Instant.parse("2026-11-27T22:30:00Z"), List.of())));

        var response = mvc.perform(get("/api/calls/RM_1/report").param("format", "csv")
                .param("timezone", "Europe/Moscow")).andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentType()).isEqualTo("text/csv;charset=UTF-8");
        assertThat(response.getHeader("Content-Disposition"))
                .isEqualTo("attachment; filename=\"attendance-RM_1-2026-11-28.csv\"");
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(response.getContentAsString(StandardCharsets.UTF_8)).contains("28.11.2026 01:30:00", "Europe/Moscow");
    }

    @Test
    void defaultsToPdf() throws Exception {
        when(store.report("RM_1")).thenReturn(Optional.of(new AttendanceReport("RM_1", Instant.now(), List.of())));
        var response = mvc.perform(get("/api/calls/RM_1/report")).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentType()).isEqualTo("application/pdf");
        assertThat(new String(response.getContentAsByteArray(), 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
    }

    @Test
    void rejectsInvalidParametersBeforeAccessingStorage() throws Exception {
        assertThat(mvc.perform(get("/api/calls/RM_1/report").param("format", "html"))
                .andReturn().getResponse().getStatus()).isEqualTo(400);
        assertThat(mvc.perform(get("/api/calls/RM_1/report").param("timezone", "somewhere/nope"))
                .andReturn().getResponse().getStatus()).isEqualTo(400);
        verifyNoInteractions(store);
    }

    @Test
    void missingOrExpiredReportReturns404() throws Exception {
        when(store.report("RM_missing")).thenReturn(Optional.empty());
        var response = mvc.perform(get("/api/calls/RM_missing/report")).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(response.getContentAsString(StandardCharsets.UTF_8)).contains("Отчёт не найден");
    }

    @Test
    void generationFailureReturnsHelpfulProblemWithoutInternalDetails() throws Exception {
        var report = new AttendanceReport("RM_1", Instant.now(), List.of());
        var renderer = mock(AttendanceReportRenderer.class);
        when(store.report("RM_1")).thenReturn(Optional.of(report));
        when(renderer.pdf(report, ZoneId.of("UTC"))).thenThrow(new ReportGenerationException(
                "internal-font-path", new java.io.IOException("internal-font-path")));
        var failingMvc = MockMvcBuilders.standaloneSetup(new ReportController(store, renderer))
                .setControllerAdvice(new ApiErrors()).build();

        var response = failingMvc.perform(get("/api/calls/RM_1/report")).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(500);
        assertThat(response.getContentType()).startsWith("application/problem+json");
        assertThat(response.getContentAsString(StandardCharsets.UTF_8))
                .contains("Не удалось сформировать отчёт").doesNotContain("internal-font-path");
    }
}
