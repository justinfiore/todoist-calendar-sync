package todoistcaldavsync.planner.domain

import groovy.json.JsonOutput
import groovy.json.JsonSlurper

import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant

/** Portable verified membership; calendar start/end edits do not rewrite this common baseline. */
final class ProjectionMetadata {
    static final String PREFIX = 'todoist-projection:'
    static final int MAX_BYTES = 16_384
    final Map value

    ProjectionMetadata(Map source) {
        Map copy = new JsonSlurper().parseText(JsonOutput.toJson(source)) as Map
        if (copy.schemaVersion != 1 || !ManagedEventIds.isPlannerUid(copy.uid?.toString()) ||
            !(copy.destination instanceof String) || !copy.destination ||
            !(copy.revision instanceof Number) || copy.revision < 1 || copy.revision != copy.revision.longValue() ||
            !(copy.routingFingerprint ==~ /[a-f0-9]{64}/) ||
            !(copy.members instanceof List) || !copy.members || copy.members.size() > 50 ||
            !(copy.historical instanceof Boolean) || !(copy.allDay instanceof Boolean)) {
            throw new IllegalArgumentException('projection metadata is unsupported or incomplete; repair required')
        }
        Set<String> keys = [] as Set
        Set<String> taskIds = [] as Set
        Instant previousEnd = null
        copy.members.each { Object row ->
            if (!(row instanceof Map) || !row.taskId || !row.occurrenceKey ||
                !row.occurrenceKey.toString().startsWith(row.taskId.toString() + ':') ||
                !keys.add(row.occurrenceKey.toString()) || !taskIds.add(row.taskId.toString()) || !(row.due instanceof Map) ||
                !row.due.date || !(row.effectiveMinutes instanceof Number) || row.effectiveMinutes < 1 ||
                row.effectiveMinutes != row.effectiveMinutes.longValue() ||
                (row.nativeMinutes != null && (!(row.nativeMinutes instanceof Number) || row.nativeMinutes < 1 ||
                    row.nativeMinutes != row.nativeMinutes.longValue()))) {
                throw new IllegalArgumentException('invalid projection membership; repair required')
            }
            String count = row.occurrenceKey.toString().substring(row.taskId.toString().length() + 1)
            if (!(count ==~ /0|[1-9][0-9]*/) || Long.parseLong(count) < 0) {
                throw new IllegalArgumentException('invalid occurrence count; repair required')
            }
            Instant start = Instant.parse(row.start.toString())
            Instant end = Instant.parse(row.end.toString())
            if (!end.isAfter(start) || (previousEnd != null && start != previousEnd) ||
                (!copy.allDay && Duration.between(start, end) != Duration.ofMinutes(row.effectiveMinutes.longValue()))) {
                throw new IllegalArgumentException('projection members must have verified consecutive positive intervals')
            }
            if (row.taskUpdated != null) Instant.parse(row.taskUpdated.toString())
            previousEnd = end
        }
        if ((copy.allDay || copy.historical) && copy.members.size() != 1) {
            throw new IllegalArgumentException('all-day and historical projections must be individual occurrences')
        }
        if (JsonOutput.toJson(copy).getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw new IllegalArgumentException('projection metadata exceeds portable membership limit')
        }
        this.value = immutable(copy)
    }

    static ProjectionMetadata read(CalendarEvent event) {
        if (!ManagedEventIds.isOwned(event, event?.calendarName)) {
            throw new IllegalStateException('projection lacks planner ownership; repair required')
        }
        List<String> lines = (event.description ?: '').readLines().findAll { it.startsWith(PREFIX) }
        if (lines.size() != 1) throw new IllegalStateException('missing or duplicate portable membership; repair required')
        try {
            String encoded = lines[0].substring(PREFIX.length())
            if (encoded.length() > MAX_BYTES * 2) throw new IllegalArgumentException('oversized metadata')
            Map raw = new JsonSlurper().parseText(new String(Base64.urlDecoder.decode(encoded), StandardCharsets.UTF_8)) as Map
            ProjectionMetadata metadata = new ProjectionMetadata(raw)
            if (metadata.value.uid != event.uid || metadata.value.destination != event.calendarName) {
                throw new IllegalArgumentException('projection identity/destination mismatch')
            }
            metadata
        } catch (Exception e) {
            throw new IllegalStateException('invalid portable projection metadata; repair required', e)
        }
    }

    String description(String humanDescription, String blockId, String planId) {
        if ((humanDescription ?: '').readLines().any { it.startsWith(PREFIX) }) {
            throw new IllegalArgumentException('human description already contains portable membership')
        }
        String encoded = Base64.urlEncoder.withoutPadding().encodeToString(JsonOutput.toJson(value).getBytes(StandardCharsets.UTF_8))
        ManagedEventIds.buildDescription(blockId, planId) + PREFIX + encoded + '\n' + (humanDescription ?: '')
    }

    static void requireUnchanged(CalendarEvent expected, CalendarEvent live) {
        requireResourceUnchanged(expected, live)
        read(live)
    }

    static void requireResourceUnchanged(CalendarEvent expected, CalendarEvent live) {
        if (expected == null || live == null || !expected.etag || !live.etag ||
            expected.calendarName != live.calendarName || expected.id != live.id || expected.uid != live.uid ||
            expected.etag != live.etag || expected.description != live.description ||
            expected.start != live.start || expected.end != live.end || expected.allDay != live.allDay ||
            expected.title != live.title) {
            throw new IllegalStateException('projection resource changed; reconciliation required')
        }
    }

    /** Only attach portable state to an unambiguous old single occurrence, without moving it. */
    static void requireSingleUpgrade(CalendarEvent expected, CalendarEvent target) {
        ProjectionMetadata metadata = read(target)
        List<String> lines = (expected.description ?: '').readLines()
        Map member = metadata.value.members[0]
        if (!ManagedEventIds.isOwned(expected, expected.calendarName) || !expected.etag ||
            lines.any { it.startsWith(PREFIX) } || metadata.value.members.size() != 1 ||
            metadata.value.historical || metadata.value.revision != 1 ||
            lines.findAll { it.startsWith(ManagedEventIds.SERIES_ID_PREFIX) } !=
                [ManagedEventIds.SERIES_ID_PREFIX + member.taskId] ||
            lines.findAll { it.startsWith(ManagedEventIds.OCCURRENCE_ID_PREFIX) } !=
                [ManagedEventIds.OCCURRENCE_ID_PREFIX + member.occurrenceKey] ||
            expected.id != target.id || expected.uid != target.uid || expected.calendarName != target.calendarName ||
            expected.start != target.start || expected.end != target.end || expected.allDay != target.allDay ||
            expected.title != target.title) {
            throw new IllegalStateException('old projection is not a verified single occurrence; repair required')
        }
    }

    /** Exact resource evidence used by journals; includes the portable baseline, never credentials. */
    static Map eventMap(CalendarEvent event) {
        [id: event.id, uid: event.uid, title: event.title, description: event.description,
            calendarName: event.calendarName, start: event.start.toString(), end: event.end.toString(),
            allDay: event.allDay, etag: event.etag, lastModified: event.lastModified?.toString()]
    }

    static String humanDescription(CalendarEvent event) {
        read(event)
        (event.description ?: '').readLines().findAll { String line ->
            line != ManagedEventIds.OWNERSHIP_MARKER && !line.startsWith(ManagedEventIds.BLOCK_ID_PREFIX) &&
                !line.startsWith(ManagedEventIds.PLAN_ID_PREFIX) && !line.startsWith(PREFIX)
        }.join('\n')
    }

    static CalendarEvent eventFromMap(Map source) {
        CalendarEvent.builder().id(source.id?.toString()).uid(source.uid?.toString())
            .title(source.title?.toString()).description(source.description?.toString())
            .calendarName(source.calendarName?.toString()).start(Instant.parse(source.start.toString()))
            .end(Instant.parse(source.end.toString())).allDay(source.allDay == true)
            .etag(source.etag?.toString())
            .lastModified(source.lastModified ? Instant.parse(source.lastModified.toString()) : null).build()
    }

    private static Object immutable(Object value) {
        if (value instanceof Map) return Collections.unmodifiableMap(value.collectEntries { k, v -> [(k): immutable(v)] })
        if (value instanceof Collection) return Collections.unmodifiableList(value.collect { immutable(it) })
        value
    }
}
