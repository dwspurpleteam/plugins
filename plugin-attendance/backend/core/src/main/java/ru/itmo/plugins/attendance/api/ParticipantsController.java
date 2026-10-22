package ru.itmo.plugins.attendance.api;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import ru.itmo.plugins.attendance.events.EventSource;
import ru.itmo.plugins.attendance.tracking.CallRegistry;
import ru.itmo.plugins.attendance.tracking.CallSnapshot;

@RestController
@RequestMapping(path = "/api", produces = MediaType.APPLICATION_JSON_VALUE)
class ParticipantsController {

    private final CallRegistry registry;
    private final EventSource source;
    private final String version;

    ParticipantsController(CallRegistry registry, EventSource source, ObjectProvider<BuildProperties> build) {
        this.registry = registry;
        this.source = source;
        BuildProperties properties = build.getIfAvailable();
        this.version = properties != null ? properties.getVersion() : "dev";
    }

    /** Состав звонка. Первый запрос по callId запускает отслеживание, UI опрашивает его периодически. */
    @GetMapping("/calls/{callId}/participants")
    CallSnapshot participants(@PathVariable String callId) {
        return registry.getOrStart(CallIds.requireValid(callId)).snapshot();
    }

    @GetMapping("/info")
    Info info() {
        return new Info(source.name(), source.describe(), version);
    }

    record Info(String source, String target, String version) {
    }
}
