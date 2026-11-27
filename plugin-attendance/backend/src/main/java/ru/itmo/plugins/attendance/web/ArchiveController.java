package ru.itmo.plugins.attendance.web;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import ru.itmo.plugins.attendance.service.tracking.AttendanceArchive;
import ru.itmo.plugins.attendance.service.tracking.AttendanceReportStore;

@RestController
class ArchiveController {
    private final AttendanceReportStore store;

    ArchiveController(AttendanceReportStore store) {
        this.store = store;
    }

    @GetMapping("/api/archive")
    ResponseEntity<AttendanceArchive> archive(@RequestParam String participantId,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int limit,
            @RequestParam(required = false) String from, @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "UTC") String timezone) {
        String subject = ArchiveParameters.participantId(participantId);
        var zone = ArchiveParameters.timezone(timezone);
        var fromDate = ArchiveParameters.date(from);
        var toDate = ArchiveParameters.date(to);
        if (page < 1 || limit < 1 || limit > 100) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Номер страницы должен быть от 1, размер страницы — от 1 до 100");
        }
        if (fromDate != null && toDate != null && fromDate.isAfter(toDate)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Дата начала периода позже даты окончания");
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(store.archive(subject, page, limit, fromDate, toDate, zone));
    }
}
