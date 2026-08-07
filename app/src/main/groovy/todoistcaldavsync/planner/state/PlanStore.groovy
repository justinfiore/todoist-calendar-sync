package todoistcaldavsync.planner.state

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import todoistcaldavsync.planner.domain.MemberInterval
import todoistcaldavsync.planner.domain.Plan
import todoistcaldavsync.planner.domain.PlanChange
import todoistcaldavsync.planner.domain.PlanningExplanation
import todoistcaldavsync.planner.domain.ScheduledBlock
import todoistcaldavsync.planner.domain.Task
import todoistcaldavsync.planner.domain.TimeSlot
import todoistcaldavsync.planner.domain.UnscheduledTask

import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant

/**
 * Local preview plan snapshot persistence. No remote I/O.
 * Stores machine-readable JSON with ISO-8601 instants.
 * Saves are atomic (temp + flush + move) so readers never see partial final JSON.
 *
 * Filenames use a collision-free encoding: readable sanitized prefix plus a stable
 * SHA-256 hex suffix of the exact UTF-8 plan id. Distinct ids never share a path.
 * Legacy bare-sanitize filenames remain loadable when the embedded snapshot id matches.
 */
class PlanStore {
    /** Current on-disk snapshot schema. Bump when wire format changes incompatibly. */
    static final int SCHEMA_VERSION = 2
    /** Hex chars of id hash used in collision-free filenames. */
    static final int FILENAME_HASH_HEX_CHARS = 16

    private final Path directory
    /**
     * Optional hook invoked after temp write/flush and before final move.
     * Used in tests to simulate failure without process crash. Null in production.
     */
    private final Runnable beforeMoveHook

    PlanStore(Path directory) {
        this(directory, null)
    }

    PlanStore(Path directory, Runnable beforeMoveHook) {
        if (directory == null) {
            throw new IllegalArgumentException('directory is required')
        }
        this.directory = directory
        this.beforeMoveHook = beforeMoveHook
    }

    Path getDirectory() {
        directory
    }

    /**
     * Canonical path for new saves: {@code plan-<readablePrefix>-<hash>.json}.
     * Never contains path separators; hash is over exact UTF-8 plan id bytes.
     */
    Path pathFor(String planId) {
        directory.resolve("plan-${encodePlanIdForFilename(planId)}.json")
    }

    /**
     * Legacy path used by older builds: {@code plan-<legacySanitize>.json}.
     */
    Path legacyPathFor(String planId) {
        directory.resolve("plan-${legacySanitize(planId)}.json")
    }

    void save(Plan plan) {
        if (plan == null) {
            throw new IllegalArgumentException('plan is required')
        }
        try {
            Files.createDirectories(directory)
        } catch (Exception e) {
            throw new PlanStoreException(
                "Failed to create plan store directory: ${directory}", directory.toString(), 'save', e)
        }
        Path target = pathFor(plan.id)
        String json = toJson(plan)
        Path temp = null
        try {
            String safeStem = encodePlanIdForFilename(plan.id)
            // Temp name must stay a single path segment (no separators).
            temp = Files.createTempFile(directory, ".plan-${safeStem}.", '.tmp')
            Files.write(temp, json.getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)
            // Force content to durable storage before publish
            try (def ch = Files.newByteChannel(temp, StandardOpenOption.WRITE)) {
                ch.force(true)
            }
            if (beforeMoveHook != null) {
                beforeMoveHook.run()
            }
            atomicReplace(temp, target)
            temp = null
        } catch (PlanStoreException e) {
            cleanupTemp(temp)
            throw e
        } catch (Exception e) {
            cleanupTemp(temp)
            throw new PlanStoreException(
                "Failed to save plan '${plan.id}' to ${target}: ${e.message}",
                target.toString(), 'save', e)
        } finally {
            cleanupTemp(temp)
        }
    }

    /**
     * Load plan by id. Returns null if snapshot file does not exist.
     * Tries collision-free path first, then legacy sanitize path when embedded id matches.
     * Throws {@link PlanStoreException} for corrupt/invalid content (never a partial Plan).
     * Never returns a snapshot whose embedded id differs from the requested id.
     */
    Plan load(String planId) {
        if (planId == null) {
            return null
        }
        Path target = pathFor(planId)
        if (Files.exists(target)) {
            Plan plan = readPlanFile(target, planId)
            if (plan != null) {
                return plan
            }
        }
        Path legacy = legacyPathFor(planId)
        if (Files.exists(legacy) && legacy != target) {
            Plan plan = readPlanFile(legacy, planId)
            if (plan != null) {
                return plan
            }
        }
        return null
    }

    private Plan readPlanFile(Path target, String expectedId) {
        String text
        try {
            text = new String(Files.readAllBytes(target), StandardCharsets.UTF_8)
        } catch (Exception e) {
            throw new PlanStoreException(
                "Failed to read plan '${expectedId}' from ${target}: ${e.message}",
                target.toString(), 'load', e)
        }
        Plan plan = parseJson(text, target.toString())
        if (plan.id != expectedId) {
            // Different plan stored under a colliding legacy name — do not return it.
            return null
        }
        return plan
    }

    /**
     * List original plan IDs from snapshot metadata (not sanitized filename stems).
     * Deterministic: sorted by original id. Corrupt snapshots that cannot yield an id are skipped.
     */
    List<String> listPlanIds() {
        if (!Files.isDirectory(directory)) {
            return []
        }
        List<String> ids = []
        Files.list(directory).withCloseable { stream ->
            stream.each { Path p ->
                def name = p.fileName.toString()
                def m = name =~ /^plan-(.+)\.json$/
                if (!m.matches()) {
                    return
                }
                String originalId = readPlanIdFromSnapshot(p)
                if (originalId != null && !originalId.isEmpty()) {
                    ids << originalId
                }
            }
        }
        return ids.toSorted()
    }

    /**
     * Read {@code id} from snapshot JSON metadata. Returns null for unreadable/corrupt files
     * so listing can skip them without failing the whole directory scan.
     */
    private static String readPlanIdFromSnapshot(Path path) {
        try {
            String text = new String(Files.readAllBytes(path), StandardCharsets.UTF_8)
            def parsed = new JsonSlurper().parseText(text)
            if (!(parsed instanceof Map)) {
                return null
            }
            def id = parsed.id
            return id != null ? id.toString() : null
        } catch (Exception ignored) {
            return null
        }
    }

    static String toJson(Plan plan) {
        def map = planToMap(plan)
        return JsonOutput.prettyPrint(JsonOutput.toJson(map))
    }

    static Map planToMap(Plan plan) {
        [
            schemaVersion  : SCHEMA_VERSION,
            id             : plan.id,
            version        : plan.version,
            createdAt      : plan.createdAt.toString(),
            mode           : plan.mode,
            tasks          : plan.tasks.collect { taskToMap(it) },
            slots          : plan.slots.collect { slotToMap(it) },
            scheduledBlocks: plan.scheduledBlocks.collect { blockToMap(it) },
            unscheduled    : plan.unscheduled.collect {
                [
                    task  : taskToMap(it.task),
                    reason: it.reason,
                    code  : it.code
                ]
            },
            changes        : plan.changes.collect { changeToMap(it) },
            explanations   : plan.explanations.collect {
                [
                    code       : it.code,
                    message    : it.message,
                    subjectType: it.subjectType,
                    subjectId  : it.subjectId,
                    details    : it.details
                ]
            },
            metrics        : plan.metrics,
            humanDiff      : plan.humanDiff
        ]
    }

    static Plan fromMap(Map root) {
        fromMap(root, null)
    }

    static Plan fromMap(Map root, String path) {
        if (root == null) {
            throw new PlanStoreException('plan map is required', path, 'parse')
        }
        try {
            validateSchema(root, path)
            requireField(root, 'id', path)
            requireField(root, 'createdAt', path)

            List<Task> tasks = (root.tasks ?: []).collect { taskFromMap(it as Map, path) }
            Map<String, Task> byId = tasks.collectEntries { [(it.id): it] }
            List<UnscheduledTask> unscheduled = (root.unscheduled ?: []).collect { u ->
                def um = u as Map
                Task t = um.task instanceof Map ? taskFromMap(um.task as Map, path) : byId[um.taskId?.toString()]
                if (t == null) {
                    throw new PlanStoreException("Unscheduled task missing for ${um}", path, 'parse')
                }
                new UnscheduledTask(t, um.reason?.toString() ?: 'unscheduled', um.code?.toString())
            }
            return Plan.builder()
                .id(root.id.toString())
                .version(root.version != null ? root.version as int : 1)
                .createdAt(parseInstant(root.createdAt, 'createdAt', path))
                .mode(root.mode?.toString() ?: 'preview')
                .tasks(tasks)
                .slots((root.slots ?: []).collect { slotFromMap(it as Map, path) })
                .scheduledBlocks((root.scheduledBlocks ?: []).collect { blockFromMap(it as Map, path) })
                .unscheduled(unscheduled)
                .changes((root.changes ?: []).collect { changeFromMap(it as Map, path) })
                .explanations((root.explanations ?: []).collect {
                    def e = it as Map
                    PlanningExplanation.of(
                        e.code?.toString(),
                        e.message?.toString(),
                        e.subjectType?.toString(),
                        e.subjectId?.toString(),
                        (e.details instanceof Map ? e.details as Map : [:])
                    )
                })
                .metrics(root.metrics instanceof Map ? new LinkedHashMap<>(root.metrics as Map) : [:])
                .humanDiff(root.humanDiff?.toString())
                .build()
        } catch (PlanStoreException e) {
            throw e
        } catch (Exception e) {
            throw new PlanStoreException(
                "Invalid plan snapshot${path ? " at ${path}" : ''}: ${e.message}",
                path, 'parse', e)
        }
    }

    private static Plan parseJson(String text, String path) {
        if (text == null || text.trim().isEmpty()) {
            throw new PlanStoreException('Plan snapshot is empty or truncated', path, 'parse')
        }
        Object root
        try {
            root = new JsonSlurper().parseText(text)
        } catch (Exception e) {
            throw new PlanStoreException(
                "Malformed or truncated JSON in plan snapshot: ${e.message}", path, 'parse', e)
        }
        if (!(root instanceof Map)) {
            throw new PlanStoreException('Plan snapshot root must be a JSON object', path, 'parse')
        }
        return fromMap(root as Map, path)
    }

    private static void validateSchema(Map root, String path) {
        if (root.schemaVersion == null) {
            // Legacy snapshots without schemaVersion are accepted as v1 and upgraded on next save.
            return
        }
        int ver
        try {
            ver = root.schemaVersion as int
        } catch (Exception e) {
            throw new PlanStoreException(
                "Invalid schemaVersion: ${root.schemaVersion}", path, 'parse', e)
        }
        if (ver < 1 || ver > SCHEMA_VERSION) {
            throw new PlanStoreException(
                "Unsupported plan snapshot schemaVersion ${ver} (supported 1..${SCHEMA_VERSION})",
                path, 'parse')
        }
    }

    private static void requireField(Map root, String field, String path) {
        if (root[field] == null || root[field].toString().trim().isEmpty()) {
            throw new PlanStoreException("Missing required field '${field}'", path, 'parse')
        }
    }

    private static Instant parseInstant(def value, String field, String path) {
        if (value == null) {
            throw new PlanStoreException("Missing required instant field '${field}'", path, 'parse')
        }
        try {
            return Instant.parse(value.toString())
        } catch (Exception e) {
            throw new PlanStoreException(
                "Invalid ISO instant for '${field}': ${value}", path, 'parse', e)
        }
    }

    private static void atomicReplace(Path temp, Path target) {
        try {
            Files.move(temp, target,
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (AtomicMoveNotSupportedException ignored) {
            // Same-filesystem non-atomic replace fallback — still never leaves partial final from write.
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private static void cleanupTemp(Path temp) {
        if (temp != null) {
            try {
                Files.deleteIfExists(temp)
            } catch (Exception ignored) {
                // best-effort
            }
        }
    }

    private static Map taskToMap(Task t) {
        [
            id              : t.id,
            content         : t.content,
            projectId       : t.projectId,
            projectName     : t.projectName,
            labels          : t.labels,
            priority        : t.priority,
            deadline        : t.deadline?.toString(),
            dueTime         : t.dueTime?.toString(),
            nativeDuration  : t.nativeDuration?.toString(),
            effectiveMinutes: t.effectiveDuration.toMinutes(),
            durationSource  : t.durationSource,
            manual          : t.manual,
            allDayDue       : t.allDayDue
        ]
    }

    private static Task taskFromMap(Map m, String path) {
        if (m == null || m.id == null) {
            throw new PlanStoreException("Task missing required id: ${m}", path, 'parse')
        }
        Duration effective
        try {
            effective = m.effectiveMinutes != null
                ? Duration.ofMinutes((m.effectiveMinutes as long))
                : Duration.parse(m.effectiveDuration?.toString() ?: 'PT30M')
        } catch (Exception e) {
            throw new PlanStoreException(
                "Invalid task duration for ${m.id}: ${e.message}", path, 'parse', e)
        }
        Duration nativeDur = null
        if (m.nativeDuration) {
            try {
                nativeDur = Duration.parse(m.nativeDuration.toString())
            } catch (Exception e) {
                throw new PlanStoreException(
                    "Invalid nativeDuration for task ${m.id}: ${m.nativeDuration}", path, 'parse', e)
            }
        }
        return Task.builder()
            .id(m.id.toString())
            .content(m.content?.toString() ?: '')
            .projectId(m.projectId?.toString())
            .projectName(m.projectName?.toString())
            .labels((m.labels ?: []) as List)
            .priority(m.priority != null ? m.priority as int : 1)
            .deadline(m.deadline ? parseInstant(m.deadline, "task[${m.id}].deadline", path) : null)
            .dueTime(m.dueTime ? parseInstant(m.dueTime, "task[${m.id}].dueTime", path) : null)
            .nativeDuration(nativeDur)
            .effectiveDuration(effective)
            .durationSource(m.durationSource?.toString() ?: 'default')
            .manual(Boolean.valueOf(m.manual?.toString() ?: 'false'))
            .allDayDue(Boolean.valueOf(m.allDayDue?.toString() ?: 'false'))
            .build()
    }

    private static Map slotToMap(TimeSlot s) {
        [
            start              : s.start.toString(),
            end                : s.end.toString(),
            softBlocked        : s.softBlocked,
            softBlockerEventIds: s.softBlockerEventIds,
            softBlockerReasons : s.softBlockerReasons,
            windowName         : s.windowName
        ]
    }

    private static TimeSlot slotFromMap(Map m, String path) {
        TimeSlot.builder()
            .start(parseInstant(m.start, 'slot.start', path))
            .end(parseInstant(m.end, 'slot.end', path))
            .softBlocked(Boolean.valueOf(m.softBlocked?.toString() ?: 'false'))
            .softBlockerEventIds((m.softBlockerEventIds ?: []) as List)
            .softBlockerReasons((m.softBlockerReasons ?: []) as List)
            .windowName(m.windowName?.toString())
            .build()
    }

    private static Map blockToMap(ScheduledBlock b) {
        [
            id             : b.id,
            start          : b.start.toString(),
            end            : b.end.toString(),
            taskIds        : b.taskIds,
            memberIntervals: b.memberIntervals.collect { it.toMap() },
            projectId      : b.projectId,
            projectName    : b.projectName,
            title          : b.title,
            focusBlock     : b.focusBlock,
            frozen         : b.frozen,
            manualOverride : b.manualOverride,
            reason         : b.reason,
            metadata       : b.metadata
        ]
    }

    private static ScheduledBlock blockFromMap(Map m, String path) {
        List<String> taskIds = (m.taskIds ?: []) as List
        List<MemberInterval> members = []
        if (m.memberIntervals instanceof Collection && !(m.memberIntervals as Collection).isEmpty()) {
            try {
                members = (m.memberIntervals as Collection).collect { MemberInterval.fromMap(it as Map) }
            } catch (Exception e) {
                throw new PlanStoreException(
                    "Invalid memberIntervals on block ${m.id}: ${e.message}", path, 'parse', e)
            }
        }
        try {
            return ScheduledBlock.builder()
                .id(m.id?.toString())
                .start(parseInstant(m.start, "block[${m.id}].start", path))
                .end(parseInstant(m.end, "block[${m.id}].end", path))
                .taskIds(taskIds)
                .memberIntervals(members)
                .projectId(m.projectId?.toString())
                .projectName(m.projectName?.toString())
                .title(m.title?.toString())
                .focusBlock(Boolean.valueOf(m.focusBlock?.toString() ?: 'false'))
                .frozen(Boolean.valueOf(m.frozen?.toString() ?: 'false'))
                .manualOverride(Boolean.valueOf(m.manualOverride?.toString() ?: 'false'))
                .reason(m.reason?.toString())
                .metadata(m.metadata instanceof Map ? new LinkedHashMap<>(m.metadata as Map) : [:])
                .build()
        } catch (PlanStoreException e) {
            throw e
        } catch (Exception e) {
            throw new PlanStoreException(
                "Invalid scheduled block ${m?.id}: ${e.message}", path, 'parse', e)
        }
    }

    private static Map changeToMap(PlanChange c) {
        [
            id            : c.id,
            type          : c.type,
            taskId        : c.taskId,
            previousStart : c.previousStart?.toString(),
            newStart      : c.newStart?.toString(),
            previousEnd   : c.previousEnd?.toString(),
            newEnd        : c.newEnd?.toString(),
            reason        : c.reason,
            metadata      : c.metadata
        ]
    }

    private static PlanChange changeFromMap(Map m, String path) {
        try {
            PlanChange.builder()
                .id(m.id?.toString())
                .type(m.type?.toString())
                .taskId(m.taskId?.toString())
                .previousStart(m.previousStart ? parseInstant(m.previousStart, 'change.previousStart', path) : null)
                .newStart(m.newStart ? parseInstant(m.newStart, 'change.newStart', path) : null)
                .previousEnd(m.previousEnd ? parseInstant(m.previousEnd, 'change.previousEnd', path) : null)
                .newEnd(m.newEnd ? parseInstant(m.newEnd, 'change.newEnd', path) : null)
                .reason(m.reason?.toString())
                .metadata(m.metadata instanceof Map ? new LinkedHashMap<>(m.metadata as Map) : [:])
                .build()
        } catch (PlanStoreException e) {
            throw e
        } catch (Exception e) {
            throw new PlanStoreException(
                "Invalid plan change ${m?.id}: ${e.message}", path, 'parse', e)
        }
    }

    /**
     * Collision-free filename stem for a plan id: readable sanitized prefix + '-' + hash.
     * Distinct UTF-8 ids always produce distinct stems. No filesystem separators.
     */
    static String encodePlanIdForFilename(String planId) {
        String raw = planId ?: 'unknown'
        String prefix = readablePrefix(raw)
        String hash = idHashHex(raw)
        return "${prefix}-${hash}"
    }

    /**
     * Legacy sanitize used by pre-collision-free builds. Public for tests/migration.
     * Maps many distinct ids onto the same stem (e.g. {@code a/b} and {@code a_b}).
     */
    static String legacySanitize(String id) {
        (id ?: 'unknown').replaceAll(/[^A-Za-z0-9._-]/, '_')
    }

    /**
     * Readable single-segment prefix derived from the id. Strips separators/traversal.
     * Empty after strip becomes {@code id}.
     */
    static String readablePrefix(String id) {
        String s = (id ?: 'unknown')
            .replace('\\', '_')
            .replace('/', '_')
            .replaceAll(/[^A-Za-z0-9._-]/, '_')
            .replaceAll(/_+/, '_')
            .replaceAll(/^_+|_+$/, '')
        if (s.isEmpty() || s == '.' || s == '..') {
            s = 'id'
        }
        // Cap prefix length so paths stay manageable; hash still disambiguates fully.
        if (s.length() > 48) {
            s = s.substring(0, 48)
        }
        return s
    }

    /** First {@link #FILENAME_HASH_HEX_CHARS} hex chars of SHA-256(UTF-8 plan id). */
    static String idHashHex(String planId) {
        MessageDigest md = MessageDigest.getInstance('SHA-256')
        byte[] dig = md.digest((planId ?: '').getBytes(StandardCharsets.UTF_8))
        def hex = dig.collect { String.format('%02x', it & 0xff) }.join()
        return hex.substring(0, FILENAME_HASH_HEX_CHARS)
    }

    /** @deprecated use {@link #legacySanitize} or {@link #encodePlanIdForFilename} */
    private static String sanitize(String id) {
        legacySanitize(id)
    }
}
