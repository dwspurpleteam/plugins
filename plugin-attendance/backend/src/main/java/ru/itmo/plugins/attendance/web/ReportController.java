package ru.itmo.plugins.attendance.web;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import ru.itmo.plugins.attendance.service.report.AttendanceReportRenderer;
import ru.itmo.plugins.attendance.service.tracking.AttendanceReport;
import ru.itmo.plugins.attendance.service.tracking.AttendanceReportStore;

@RestController
class ReportController {
    private final AttendanceReportStore store;
    private final AttendanceReportRenderer renderer;

    ReportController(AttendanceReportStore store, AttendanceReportRenderer renderer) {
        this.store = store;
        this.renderer = renderer;
    }

    @GetMapping("/api/calls/{callId}/report")
    ResponseEntity<byte[]> report(@PathVariable String callId,
            @RequestParam(defaultValue = "pdf") String format,
            @RequestParam(defaultValue = "UTC") String timezone) {
        return export(callId, format, timezone, null);
    }

    @GetMapping("/api/archive/calls/{callId}/report")
    ResponseEntity<byte[]> archiveReport(@PathVariable String callId, @RequestParam String participantId,
            @RequestParam(defaultValue = "pdf") String format,
            @RequestParam(defaultValue = "UTC") String timezone) {
        return export(callId, format, timezone, ArchiveParameters.participantId(participantId));
    }

    private ResponseEntity<byte[]> export(String callId, String format, String timezone, String participantId) {
        String validCallId = CallIds.requireValid(callId);
        String extension = format.toLowerCase(Locale.ROOT);
        if (!extension.equals("pdf") && !extension.equals("csv")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Формат отчёта должен быть pdf или csv");
        }
        ZoneId zone = ArchiveParameters.timezone(timezone);
        var report = store.report(validCallId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                "Отчёт не найден или срок его хранения истёк"));
        if (participantId != null && !containsParticipant(report, participantId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Отчёт доступен только участникам звонка");
        }
        String safeCall = validCallId.replaceAll("[^A-Za-z0-9_-]", "_");
        if (safeCall.length() > 80) safeCall = safeCall.substring(0, 80);
        String filename = "attendance-" + safeCall + "-"
                + DateTimeFormatter.ofPattern("uuuu-MM-dd").format(report.generatedAt().atZone(zone)) + "." + extension;
        byte[] bytes = extension.equals("pdf") ? renderer.pdf(report, zone) : renderer.csv(report, zone);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header("Content-Disposition", ContentDisposition.attachment().filename(filename).build().toString())
                .contentType(extension.equals("pdf") ? MediaType.APPLICATION_PDF
                        : MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .contentLength(bytes.length).body(bytes);
    }

    private static boolean containsParticipant(AttendanceReport report, String participantId) {
        return report.sessions().stream().flatMap(session -> session.visits().stream())
                .anyMatch(visit -> visit.participantId().equals(participantId));
    }
}
