package ru.itmo.plugins.attendance.service.tracking;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import ru.itmo.plugins.attendance.configuration.ReportStorageProperties;

/**
 * One atomically replaced JSON file per tracking attempt. The in-memory index contains only IDs
 * and retention timestamps. A file lock prevents two processes from recovering each other's live sessions.
 */
@Component
public class AttendanceReportStore implements AutoCloseable, DisposableBean {
    private static final int SCHEMA_VERSION = 1;
    private static final Set<Path> OWNED_DIRECTORIES = ConcurrentHashMap.newKeySet();

    private final ReportStorageProperties properties;
    private final Clock clock;
    private final JsonMapper json = JsonMapper.builder().build();
    private final Map<String, Map<UUID, Entry>> calls = new HashMap<>();
    private final Map<String, Set<UUID>> failedWrites = new HashMap<>();
    private FileChannel ownershipChannel;
    private FileLock ownershipLock;
    private Path ownedDirectory;
    private boolean closed;

    public AttendanceReportStore(ReportStorageProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
        try {
            Files.createDirectories(properties.directory());
            acquireOwnership();
            loadArchive();
            cleanup();
            Path probe = Files.createTempFile(properties.directory(), ".write-check-", ".tmp");
            Files.delete(probe);
        } catch (IOException | RuntimeException exception) {
            try {
                close();
            } catch (RuntimeException cleanupFailure) {
                exception.addSuppressed(cleanupFailure);
            }
            throw new IllegalStateException("Cannot initialize attendance report storage " + properties.directory(), exception);
        }
    }

    private void acquireOwnership() throws IOException {
        Path canonicalDirectory = properties.directory().toRealPath();
        if (!OWNED_DIRECTORIES.add(canonicalDirectory)) {
            throw new IOException("Attendance report directory is already in use");
        }
        ownedDirectory = canonicalDirectory;
        ownershipChannel = FileChannel.open(properties.directory().resolve(".attendance.lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            ownershipLock = ownershipChannel.tryLock();
        } catch (OverlappingFileLockException exception) {
            throw new IOException("Attendance report directory is already in use", exception);
        }
        if (ownershipLock == null) {
            throw new IOException("Attendance report directory is already in use");
        }
    }

    private void loadArchive() throws IOException {
        try (var paths = Files.list(properties.directory())) {
            for (Path path : paths.filter(p -> p.getFileName().toString().endsWith(".json")).toList()) {
                Stored stored = read(path);
                AttendanceReport.Session session = stored.session();
                if (session.stoppedAt() == null) {
                    save(stored.callId(), recoverInterrupted(session));
                } else {
                    index(stored.callId(), session);
                }
            }
        }
    }

    private static AttendanceReport.Session recoverInterrupted(AttendanceReport.Session session) {
        var gaps = session.gaps().stream().map(gap -> gap.to() == null
                ? new CallSnapshot.Gap(gap.from(), session.capturedAt()) : gap).toList();
        return new AttendanceReport.Session(session.sessionId(), session.activationId(), session.activatedAt(),
                session.trackingSince(), session.endedAt(), session.capturedAt(), session.capturedAt(), CallStatus.FAILED,
                "Отслеживание прервано перезапуском сервиса; данные сохранены до последней записи", session.visits(), gaps);
    }

    public synchronized void save(String callId, AttendanceReport.Session session) {
        requireOpen();
        UUID id = session.sessionId();
        try {
            Stored stored = new Stored(SCHEMA_VERSION, callId, session);
            validate(stored);
            writeAtomically(id, json.writeValueAsBytes(stored));
            index(callId, session);
            Set<UUID> failures = failedWrites.get(callId);
            if (failures != null && failures.remove(id) && failures.isEmpty()) {
                failedWrites.remove(callId);
            }
        } catch (IOException | RuntimeException exception) {
            failedWrites.computeIfAbsent(callId, ignored -> new HashSet<>()).add(id);
            throw new ReportStorageException("Cannot save attendance history for call " + callId, exception);
        }
    }

    private void writeAtomically(UUID id, byte[] data) throws IOException {
        Path temporary = Files.createTempFile(properties.directory(), id + "-", ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(data);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            Files.move(temporary, file(id), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private void index(String callId, AttendanceReport.Session session) {
        calls.computeIfAbsent(callId, ignored -> new HashMap<>())
                .put(session.sessionId(), new Entry(session.sessionId(), session.stoppedAt()));
    }

    /** Reads durable snapshots of this call only; it never acquires live tracker locks. */
    public synchronized Optional<AttendanceReport> report(String callId) {
        requireOpen();
        if (failedWrites.containsKey(callId)) {
            throw new ReportStorageException("Attendance history could not be saved for call " + callId);
        }
        Map<UUID, Entry> entries = calls.get(callId);
        if (entries == null) {
            return Optional.empty();
        }
        var sessions = new ArrayList<AttendanceReport.Session>();
        Instant now = clock.instant();
        try {
            for (Entry entry : entries.values()) {
                if (!expired(entry, now)) {
                    Stored stored = read(file(entry.sessionId()));
                    if (!callId.equals(stored.callId())) {
                        throw new IOException("Attendance history belongs to a different call");
                    }
                    sessions.add(stored.session());
                }
            }
        } catch (IOException | RuntimeException exception) {
            throw new ReportStorageException("Cannot read attendance history for call " + callId, exception);
        }
        sessions.sort(Comparator.comparing(AttendanceReport.Session::activatedAt)
                .thenComparing(AttendanceReport.Session::sessionId));
        return sessions.isEmpty() ? Optional.empty() : Optional.of(new AttendanceReport(callId, now, sessions));
    }

    public synchronized void cleanup() {
        requireOpen();
        Instant now = clock.instant();
        try {
            for (var callIterator = calls.entrySet().iterator(); callIterator.hasNext();) {
                Map<UUID, Entry> entries = callIterator.next().getValue();
                for (var iterator = entries.values().iterator(); iterator.hasNext();) {
                    Entry entry = iterator.next();
                    if (expired(entry, now)) {
                        Files.deleteIfExists(file(entry.sessionId()));
                        iterator.remove();
                    }
                }
                if (entries.isEmpty()) {
                    callIterator.remove();
                }
            }
        } catch (IOException exception) {
            throw new ReportStorageException("Cannot clean attendance report storage", exception);
        }
    }

    private boolean expired(Entry entry, Instant now) {
        return !properties.retention().isZero() && entry.stoppedAt() != null
                && Duration.between(entry.stoppedAt(), now).compareTo(properties.retention()) >= 0;
    }

    private Path file(UUID id) {
        return properties.directory().resolve(id + ".json");
    }

    private Stored read(Path path) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Attendance history must be a regular file: " + path);
        }
        Stored stored = json.readValue(Files.readAllBytes(path), Stored.class);
        validate(stored);
        if (!path.getFileName().toString().equals(stored.session().sessionId() + ".json")) {
            throw new IOException("Attendance session filename does not match its ID: " + path);
        }
        return stored;
    }

    private static void validate(Stored stored) throws IOException {
        if (stored == null || stored.schemaVersion() != SCHEMA_VERSION || stored.callId() == null
                || stored.callId().isBlank() || stored.session() == null) {
            throw new IOException("Invalid or unsupported attendance history");
        }
        AttendanceReport.Session session = stored.session();
        if (session.sessionId() == null || session.activatedAt() == null || session.capturedAt() == null
                || session.status() == null) {
            throw new IOException("Attendance session has missing required fields");
        }
        for (AttendanceReport.Visit visit : session.visits()) {
            if (visit.participantId() == null || visit.participantId().isBlank()
                    || (visit.joinedAt() == null && visit.leftAt() == null)
                    || (visit.joinedAt() != null && visit.leftAt() != null && visit.leftAt().isBefore(visit.joinedAt()))) {
                throw new IOException("Attendance visit has invalid identity or timestamps");
            }
        }
        for (CallSnapshot.Gap gap : session.gaps()) {
            if (gap.from() == null) {
                throw new IOException("Attendance connection gap has no start time");
            }
        }
    }

    private void requireOpen() {
        if (closed) {
            throw new ReportStorageException("Attendance report storage is closed");
        }
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            if (ownershipLock != null) {
                ownershipLock.release();
            }
        } catch (IOException exception) {
            throw new ReportStorageException("Cannot release attendance storage lock", exception);
        } finally {
            try {
                if (ownershipChannel != null) ownershipChannel.close();
            } catch (IOException exception) {
                throw new ReportStorageException("Cannot close attendance storage lock file", exception);
            } finally {
                if (ownedDirectory != null) OWNED_DIRECTORIES.remove(ownedDirectory);
            }
        }
    }

    @Override
    public void destroy() {
        close();
    }

    private record Entry(UUID sessionId, Instant stoppedAt) { }
    private record Stored(int schemaVersion, String callId, AttendanceReport.Session session) { }
}
