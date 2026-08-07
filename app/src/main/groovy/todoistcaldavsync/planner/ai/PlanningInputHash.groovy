package todoistcaldavsync.planner.ai

import groovy.json.JsonOutput
import todoistcaldavsync.planner.domain.CalendarEvent
import todoistcaldavsync.planner.domain.Plan
import todoistcaldavsync.planner.domain.PlanHash
import todoistcaldavsync.planner.domain.Task

import java.time.Duration
import java.time.Instant
import java.time.temporal.TemporalAccessor

/**
 * Versioned canonical identity for the complete input that can influence AI planning advice.
 * Collection order is ignored only where the domain represents a set (tasks, slots, events,
 * labels, blocker ids/reasons and top-level plan result collections).
 */
final class PlanningInputHash {
    static final int SCHEMA_VERSION = 1

    private PlanningInputHash() {}

    static String compute(Plan plan, Collection<CalendarEvent> events = []) {
        AiValues.sha256(canonicalize(plan, events))
    }

    static String taskSetHash(Collection<Task> tasks) {
        AiValues.sha256(JsonOutput.toJson(normalize([
            schemaVersion: SCHEMA_VERSION,
            tasks: sorted((tasks ?: []).findAll { it != null }.collect { taskMap(it) })
        ])))
    }

    static String canonicalize(Plan plan, Collection<CalendarEvent> events = []) {
        if (plan == null) throw new IllegalArgumentException('plan is required')
        Map root = [
            schemaVersion: SCHEMA_VERSION,
            plan: planMap(plan),
            events: sorted((events ?: []).findAll { it != null }.collect { eventMap(it) })
        ]
        JsonOutput.toJson(normalize(root))
    }

    private static Map planMap(Plan p) {
        List slots = (p.slots ?: []).collect { s -> [
            start: instant(s.start), end: instant(s.end), softBlocked: s.softBlocked,
            softBlockerEventIds: (s.softBlockerEventIds ?: []).toSorted(),
            softBlockerReasons: (s.softBlockerReasons ?: []).toSorted(), windowName: s.windowName
        ] }
        [
            id: p.id,
            version: p.version,
            planHash: PlanHash.compute(p),
            createdAt: instant(p.createdAt),
            mode: p.mode,
            tasks: sorted((p.tasks ?: []).collect { taskMap(it) }),
            slots: sorted(slots),
            planningRange: slots ? [
                start: slots.collect { it.start }.min(),
                end: slots.collect { it.end }.max()
            ] : [start: null, end: null],
            scheduledBlocks: sorted((p.scheduledBlocks ?: []).collect { b -> [
                id: b.id, start: instant(b.start), end: instant(b.end), taskIds: new ArrayList<>(b.taskIds ?: []),
                memberIntervals: (b.memberIntervals ?: []).collect { mi ->
                    [taskId: mi.taskId, start: instant(mi.start), end: instant(mi.end)]
                },
                projectId: b.projectId, projectName: b.projectName, title: b.title,
                focusBlock: b.focusBlock, frozen: b.frozen, manualOverride: b.manualOverride,
                reason: b.reason, metadata: b.metadata
            ] }),
            unscheduled: sorted((p.unscheduled ?: []).collect { u -> [
                task: taskMap(u.task), reason: u.reason, code: u.code, metadata: u.metadata
            ] }),
            changes: sorted((p.changes ?: []).collect { c -> [
                id: c.id, type: c.type, taskId: c.taskId, previousStart: instant(c.previousStart),
                newStart: instant(c.newStart), previousEnd: instant(c.previousEnd), newEnd: instant(c.newEnd),
                reason: c.reason, metadata: c.metadata
            ] }),
            explanations: sorted((p.explanations ?: []).collect { e -> [
                code: e.code, message: e.message, subjectType: e.subjectType,
                subjectId: e.subjectId, details: e.details
            ] }),
            metrics: p.metrics,
            humanDiff: p.humanDiff
        ]
    }

    private static Map taskMap(Task t) {
        if (t == null) return null
        [
            id: t.id, content: t.content, projectId: t.projectId, projectName: t.projectName,
            labels: (t.labels ?: []).toSorted(), priority: t.priority,
            deadline: instant(t.deadline), dueTime: instant(t.dueTime),
            nativeDuration: duration(t.nativeDuration), effectiveDuration: duration(t.effectiveDuration),
            durationSource: t.durationSource, manual: t.manual, allDayDue: t.allDayDue
        ]
    }

    private static Map eventMap(CalendarEvent e) {
        [
            id: e.id, uid: e.uid, title: e.title, description: e.description, calendarName: e.calendarName,
            start: instant(e.start), end: instant(e.end), allDay: e.allDay,
            role: e.role?.name(), matchedRuleName: e.matchedRuleName,
            classificationReason: e.classificationReason, bufferBeforeMinutes: e.bufferBeforeMinutes,
            bufferAfterMinutes: e.bufferAfterMinutes, unknownCalendar: e.unknownCalendar
        ]
    }

    private static List sorted(Collection values) {
        (values ?: []).collect { normalize(it) }.toSorted { a, b ->
            JsonOutput.toJson(a) <=> JsonOutput.toJson(b)
        }
    }

    private static Object normalize(Object value) {
        if (value == null || value instanceof String || value instanceof Boolean || value instanceof Number) return value
        if (value instanceof Instant) return instant(value as Instant)
        if (value instanceof Duration) return duration(value as Duration)
        if (value instanceof Enum) return (value as Enum).name()
        if (value instanceof Map) {
            Map out = new TreeMap<String,Object>()
            (value as Map).each { k, v -> out[k.toString()] = normalize(v) }
            return out
        }
        if (value instanceof Set) return sorted(value as Set)
        if (value instanceof Collection) return (value as Collection).collect { normalize(it) }
        if (value.getClass().isArray()) {
            int length = java.lang.reflect.Array.getLength(value)
            return (0..<length).collect { normalize(java.lang.reflect.Array.get(value, it)) }
        }
        if (value instanceof TemporalAccessor) return value.toString()
        throw new IllegalArgumentException("unsupported planning input value: ${value.getClass().name}")
    }

    private static String instant(Instant value) { value?.toString() }
    private static String duration(Duration value) { value?.toString() }
}
