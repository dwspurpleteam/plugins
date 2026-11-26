package ru.itmo.plugins.attendance.configuration;

import java.nio.file.Path;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("attendance.reports")
public record ReportStorageProperties(Path directory, Duration retention) {
    public ReportStorageProperties {
        directory = (directory == null ? Path.of("data", "attendance") : directory).toAbsolutePath().normalize();
        retention = retention == null ? Duration.ofDays(30) : retention;
        if (retention.isNegative()) {
            throw new IllegalArgumentException("attendance.reports.retention must be nonnegative; 0 disables deletion");
        }
    }
}
