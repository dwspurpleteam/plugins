package ru.itmo.plugins.attendance.web;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import ru.itmo.plugins.attendance.service.events.EventSource;
import ru.itmo.plugins.attendance.service.tracking.CallRegistry;
import ru.itmo.plugins.attendance.service.tracking.CallSnapshot;

@RestController
@RequestMapping(path = "/api", produces = MediaType.APPLICATION_JSON_VALUE)
class ParticipantsController {

    private final CallRegistry registry;
    private final EventSource source;
    private final String version;

    ParticipantsController(CallRegistry registry, EventSource source,
            ObjectProvider<BuildProperties> build) {
        this.registry = registry;
        this.source = source;
        BuildProperties properties = build.getIfAvailable();
        this.version = properties != null ? properties.getVersion() : "dev";
    }

    /** Read-only snapshot; UI requests never create or extend an activation. */
    @GetMapping("/calls/{callId}/participants")
    CallSnapshot participants(@PathVariable String callId) {
        return registry.find(CallIds.requireValid(callId)).orElseThrow(() -> new ApiException(
                HttpStatus.NOT_FOUND, "Call is not activated; send POST /control first")).snapshot();
    }

    @GetMapping("/info")
    Info info() {
        return new Info(source.name(), source.describe(), version);
    }

    record Info(String source, String target, String version) {
    }
}
