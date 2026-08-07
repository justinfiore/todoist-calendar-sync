package todoistcaldavsync.planner.adapters

import groovy.json.JsonSlurper
import groovy.yaml.YamlSlurper
import todoistcaldavsync.planner.domain.CalendarEvent
import todoistcaldavsync.planner.domain.Task

import java.time.Instant
import java.time.ZoneId

/**
 * Fixture-backed read-only calendar gateway. Never contacts remote systems.
 * Date-only and zone-less local datetimes are interpreted in the configured fixture timezone
 * (typically planner config timezone), never implicit UTC.
 */
class FixtureCalendarGateway implements CalendarReadGateway {
    private final List<CalendarEvent> events
    private final ZoneId timezone

    FixtureCalendarGateway(List<CalendarEvent> events, ZoneId timezone = ZoneId.of('UTC')) {
        this.events = (events ?: []).asImmutable()
        this.timezone = timezone ?: ZoneId.of('UTC')
    }

    static FixtureCalendarGateway fromFile(File file, ZoneId timezone = null) {
        if (file == null || !file.exists()) {
            throw new IllegalArgumentException("Calendar fixture not found: ${file}")
        }
        def name = file.name.toLowerCase()
        def parsed
        if (name.endsWith('.json')) {
            parsed = new JsonSlurper().parse(file)
        } else {
            parsed = new YamlSlurper().parse(file)
        }
        ZoneId zone = timezone
        def list
        if (parsed instanceof List) {
            list = parsed
        } else if (parsed instanceof Map && parsed.events instanceof List) {
            list = parsed.events
            if (zone == null && parsed.timezone) {
                zone = Task.parseZoneId(parsed.timezone.toString(), 'fixture')
            }
        } else {
            throw new IllegalArgumentException("Calendar fixture must be a list or {events: [...]}")
        }
        zone = zone ?: ZoneId.of('UTC')
        def events = list.collect { Map raw -> parseEvent(raw as Map, zone) }
        return new FixtureCalendarGateway(events, zone)
    }

    static CalendarEvent parseEvent(Map raw, ZoneId timezone = ZoneId.of('UTC')) {
        def id = raw.id?.toString()?.trim() ?: raw.uid?.toString()?.trim()
        if (!id) {
            throw new IllegalArgumentException('Event id is required (missing/blank id and uid)')
        }
        ZoneId zone = timezone ?: ZoneId.of('UTC')
        def start = parseInstant(raw.start ?: raw.dtstart, zone, 'start')
        def end = parseInstant(raw.end ?: raw.dtend, zone, 'end')
        boolean allDay = raw.allDay == true || raw.all_day == true
        def startStr = (raw.start ?: raw.dtstart)?.toString()
        if (startStr && !startStr.contains('T') && !startStr.contains('Z')) {
            allDay = true
        }
        return CalendarEvent.builder()
            .id(id)
            .uid(raw.uid?.toString())
            .title(raw.title?.toString() ?: raw.summary?.toString() ?: '')
            .description(raw.description?.toString() ?: '')
            .calendarName(raw.calendar?.toString() ?: raw.calendarName?.toString() ?: 'Unknown')
            .start(start)
            .end(end)
            .allDay(allDay)
            .build()
    }

    private static Instant parseInstant(def value, ZoneId zone, String fieldName) {
        if (value == null) {
            throw new IllegalArgumentException("Event ${fieldName} required")
        }
        if (value instanceof Instant) {
            return value
        }
        def s = value.toString().trim()
        // Date-only requires an explicit timezone (planner/fixture zone)
        if (s.matches(/^\d{4}-\d{2}-\d{2}$/) && zone == null) {
            throw new IllegalArgumentException(
                "Event ${fieldName} date-only value '${s}' requires a fixture/planner timezone")
        }
        return Task.parseFlexibleInstant(s, false, zone)
    }

    ZoneId getTimezone() {
        timezone
    }

    @Override
    List<CalendarEvent> fetchEvents(Instant rangeStart, Instant rangeEnd) {
        events.findAll { ev ->
            ev.end.isAfter(rangeStart) && ev.start.isBefore(rangeEnd)
        }
    }
}
