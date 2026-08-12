package todoistcaldavsync.planner.scheduling

import todoistcaldavsync.planner.config.PlannerConfig
import todoistcaldavsync.planner.domain.CalendarEvent
import todoistcaldavsync.planner.domain.EventRole
import todoistcaldavsync.planner.domain.PlanningExplanation
import todoistcaldavsync.planner.domain.TimeSlot

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Generates working-window slots, subtracts hard blockers (+ buffers),
 * models soft blockers as available-but-penalized, ignores informational capacity use,
 * and treats managed_output safely (does not free capacity; excludes as occupied like hard
 * only when overlapping — managed output is treated as occupied/hard for slot subtraction
 * so planner-owned blocks are not double-booked; they remain explainable).
 */
class AvailabilityCalculator {
    private final PlannerConfig config

    AvailabilityCalculator(PlannerConfig config) {
        if (config == null) {
            throw new IllegalArgumentException('PlannerConfig is required')
        }
        this.config = config
    }

    /**
     * @param rangeStart inclusive horizon start (instant)
     * @param rangeEnd exclusive horizon end (instant)
     * @param classifiedEvents already-classified events
     */
    AvailabilityResult calculate(Instant rangeStart, Instant rangeEnd, List<CalendarEvent> classifiedEvents) {
        if (rangeStart == null || rangeEnd == null || !rangeEnd.isAfter(rangeStart)) {
            throw new IllegalArgumentException('rangeStart/rangeEnd must form a positive interval')
        }
        def events = classifiedEvents ?: []
        ZoneId zone = config.timezone

        List<TimeSlot> workingSlots = generateWorkingWindowSlots(rangeStart, rangeEnd, zone)
        List<PlanningExplanation> explanations = []

        // Partition events by capacity effect
        List<Interval> hardIntervals = []
        List<CalendarEvent> softEvents = []
        List<CalendarEvent> informational = []
        List<CalendarEvent> managed = []

        events.each { ev ->
            switch (ev.role) {
                case EventRole.HARD_BLOCKER:
                    hardIntervals << new Interval(ev.bufferedStart(), ev.bufferedEnd(), ev)
                    explanations << PlanningExplanation.of(
                        'capacity_consumed',
                        "Hard blocker '${ev.title}' on ${ev.calendarName} consumes ${ev.durationMinutes()}m" +
                            (ev.bufferBeforeMinutes || ev.bufferAfterMinutes
                                ? " plus buffers before=${ev.bufferBeforeMinutes}m after=${ev.bufferAfterMinutes}m"
                                : ''),
                        'event', ev.id,
                        [role: ev.role.configValue, matchedRuleName: ev.matchedRuleName]
                    )
                    break
                case EventRole.MANAGED_OUTPUT:
                    // Treat as occupied so we do not double-book planner output
                    hardIntervals << new Interval(ev.start, ev.end, ev)
                    managed << ev
                    explanations << PlanningExplanation.of(
                        'managed_output_occupied',
                        "Managed output '${ev.title}' on ${ev.calendarName} occupies ${ev.durationMinutes()}m (not free capacity)",
                        'event', ev.id,
                        [role: ev.role.configValue, matchedRuleName: ev.matchedRuleName]
                    )
                    break
                case EventRole.SOFT_BLOCKER:
                    softEvents << ev
                    explanations << PlanningExplanation.of(
                        'soft_blocker_penalized',
                        "Soft blocker '${ev.title}' on ${ev.calendarName} leaves capacity available but penalized" +
                            (ev.bufferBeforeMinutes || ev.bufferAfterMinutes
                                ? " (buffers before=${ev.bufferBeforeMinutes}m after=${ev.bufferAfterMinutes}m expand penalized interval)"
                                : ''),
                        'event', ev.id,
                        [role: ev.role.configValue, matchedRuleName: ev.matchedRuleName,
                         bufferBefore: ev.bufferBeforeMinutes, bufferAfter: ev.bufferAfterMinutes]
                    )
                    break
                case EventRole.INFORMATIONAL:
                    // Buffers are rejected at config load; informational never consumes capacity
                    informational << ev
                    explanations << PlanningExplanation.of(
                        'informational_no_capacity',
                        "Informational event '${ev.title}' on ${ev.calendarName} consumes no capacity",
                        'event', ev.id,
                        [role: ev.role.configValue, matchedRuleName: ev.matchedRuleName, unknownCalendar: ev.unknownCalendar]
                    )
                    break
                default:
                    explanations << PlanningExplanation.of(
                        'unhandled_role',
                        "Event '${ev.title}' has unhandled role ${ev.role}",
                        'event', ev.id, [:]
                    )
            }
            if (ev.unknownCalendar) {
                explanations << PlanningExplanation.of(
                    'unknown_calendar_diagnostic',
                    "Unknown calendar '${ev.calendarName}' for event '${ev.title}' — not silently treated as free time (fallback ${ev.role.configValue})",
                    'event', ev.id,
                    [calendarName: ev.calendarName, role: ev.role.configValue]
                )
            }
        }

        List<TimeSlot> freeAfterHard = subtractIntervals(workingSlots, hardIntervals)
        List<TimeSlot> annotated = annotateSoftBlockers(freeAfterHard, softEvents)

        long usableMinutes = sumMinutes(annotated)
        long softPenalizedMinutes = sumMinutes(annotated.findAll { it.softBlocked })

        return new AvailabilityResult(
            annotated,
            explanations,
            usableMinutes,
            softPenalizedMinutes,
            informational,
            managed,
            softEvents
        )
    }

    private List<TimeSlot> generateWorkingWindowSlots(Instant rangeStart, Instant rangeEnd, ZoneId zone) {
        List<TimeSlot> slots = []
        ZonedDateTime cursor = rangeStart.atZone(zone).toLocalDate().atStartOfDay(zone)
        ZonedDateTime endZ = rangeEnd.atZone(zone)

        while (cursor.isBefore(endZ)) {
            LocalDate date = cursor.toLocalDate()
            def dow = date.dayOfWeek
            List<NamedInterval> dayIntervals = []
            config.workingWindows.findAll { it.dayOfWeek == dow }.each { ww ->
                ZonedDateTime ws = date.atTime(ww.start).atZone(zone)
                ZonedDateTime we = date.atTime(ww.end).atZone(zone)
                Instant s = ws.toInstant()
                Instant e = we.toInstant()
                if (e.isAfter(rangeStart) && s.isBefore(rangeEnd)) {
                    Instant cs = s.isBefore(rangeStart) ? rangeStart : s
                    Instant ce = e.isAfter(rangeEnd) ? rangeEnd : e
                    if (ce.isAfter(cs)) {
                        dayIntervals << new NamedInterval(cs, ce, "${ww.groupName} ${ww.start}-${ww.end}")
                    }
                }
            }
            // Union/merge overlapping or adjacent windows for this date so capacity is counted once
            mergeNamedIntervals(dayIntervals).each { ni ->
                slots << TimeSlot.builder()
                    .start(ni.start)
                    .end(ni.end)
                    .windowName(ni.name)
                    .softBlocked(false)
                    .build()
            }
            cursor = cursor.plusDays(1)
        }
        return slots
    }

    /**
     * Merge overlapping or adjacent named intervals. Combined names are a sorted,
     * de-duplicated join with " + " for stable deterministic output.
     */
    static List<NamedInterval> mergeNamedIntervals(List<NamedInterval> intervals) {
        if (!intervals) {
            return []
        }
        def sorted = intervals.toSorted { a, b ->
            def c = a.start <=> b.start
            c != 0 ? c : a.end <=> b.end
        }
        List<NamedInterval> merged = []
        NamedInterval cur = sorted[0]
        for (int i = 1; i < sorted.size(); i++) {
            def next = sorted[i]
            // Overlap or adjacency (next.start <= cur.end)
            if (!next.start.isAfter(cur.end)) {
                Instant end = next.end.isAfter(cur.end) ? next.end : cur.end
                cur = new NamedInterval(cur.start, end, combineWindowNames(cur.name, next.name))
            } else {
                merged << cur
                cur = next
            }
        }
        merged << cur
        return merged
    }

    static String combineWindowNames(String a, String b) {
        TreeSet<String> parts = new TreeSet<>()
        (a ?: '').split(/\s*\+\s*/).each { p ->
            def t = p?.trim()
            if (t) {
                parts.add(t)
            }
        }
        (b ?: '').split(/\s*\+\s*/).each { p ->
            def t = p?.trim()
            if (t) {
                parts.add(t)
            }
        }
        return parts.join(' + ')
    }

    static final class NamedInterval {
        final Instant start
        final Instant end
        final String name

        NamedInterval(Instant start, Instant end, String name) {
            this.start = start
            this.end = end
            this.name = name
        }
    }

    private static List<TimeSlot> subtractIntervals(List<TimeSlot> slots, List<Interval> blockers) {
        if (!blockers) {
            return slots
        }
        List<TimeSlot> result = []
        slots.each { slot ->
            List<TimeSlot> remaining = [slot]
            blockers.each { blocker ->
                List<TimeSlot> next = []
                remaining.each { r ->
                    next.addAll(subtractOne(r, blocker))
                }
                remaining = next
            }
            result.addAll(remaining)
        }
        return result
    }

    private static List<TimeSlot> subtractOne(TimeSlot slot, Interval blocker) {
        // no overlap
        if (!blocker.end.isAfter(slot.start) || !blocker.start.isBefore(slot.end)) {
            return [slot]
        }
        List<TimeSlot> parts = []
        // left remnant
        if (blocker.start.isAfter(slot.start)) {
            parts << TimeSlot.builder()
                .start(slot.start)
                .end(blocker.start.isBefore(slot.end) ? blocker.start : slot.end)
                .windowName(slot.windowName)
                .softBlocked(slot.softBlocked)
                .softBlockerEventIds(slot.softBlockerEventIds)
                .softBlockerReasons(slot.softBlockerReasons)
                .build()
        }
        // right remnant
        if (blocker.end.isBefore(slot.end)) {
            parts << TimeSlot.builder()
                .start(blocker.end.isAfter(slot.start) ? blocker.end : slot.start)
                .end(slot.end)
                .windowName(slot.windowName)
                .softBlocked(slot.softBlocked)
                .softBlockerEventIds(slot.softBlockerEventIds)
                .softBlockerReasons(slot.softBlockerReasons)
                .build()
        }
        // Filter zero-length (builder rejects non-positive; guard)
        return parts.findAll { it.end.isAfter(it.start) }
    }

    /**
     * Split free slots at every soft-event boundary so free and soft-penalized segments
     * are exact. Overlapping soft blockers contribute union minutes only (no double-count).
     */
    private static List<TimeSlot> annotateSoftBlockers(List<TimeSlot> slots, List<CalendarEvent> softEvents) {
        if (!softEvents) {
            return slots
        }
        List<TimeSlot> result = []
        slots.each { slot ->
            result.addAll(splitSlotBySoftEvents(slot, softEvents))
        }
        return result
    }

    private static List<TimeSlot> splitSlotBySoftEvents(TimeSlot slot, List<CalendarEvent> softEvents) {
        // Soft buffers expand the penalized interval consistently with hard-blocker buffers
        def overlapping = softEvents.findAll { ev ->
            ev.bufferedEnd().isAfter(slot.start) && ev.bufferedStart().isBefore(slot.end)
        }
        if (!overlapping) {
            return [slot]
        }

        TreeSet<Instant> bounds = new TreeSet<>()
        bounds.add(slot.start)
        bounds.add(slot.end)
        overlapping.each { ev ->
            Instant bs = ev.bufferedStart()
            Instant be = ev.bufferedEnd()
            if (bs.isAfter(slot.start) && bs.isBefore(slot.end)) {
                bounds.add(bs)
            }
            if (be.isAfter(slot.start) && be.isBefore(slot.end)) {
                bounds.add(be)
            }
        }

        List<Instant> points = new ArrayList<>(bounds)
        List<TimeSlot> segments = []
        for (int i = 0; i < points.size() - 1; i++) {
            Instant segStart = points[i]
            Instant segEnd = points[i + 1]
            if (!segEnd.isAfter(segStart)) {
                continue
            }
            def covering = overlapping.findAll { ev ->
                ev.bufferedStart().isBefore(segEnd) && ev.bufferedEnd().isAfter(segStart)
            }
            if (covering) {
                // Deterministic ordering independent of gateway encounter order
                def ordered = covering.toSorted { a, b ->
                    def c = (a.id ?: '') <=> (b.id ?: '')
                    c != 0 ? c : (a.title ?: '') <=> (b.title ?: '')
                }
                segments << TimeSlot.builder()
                    .start(segStart)
                    .end(segEnd)
                    .windowName(slot.windowName)
                    .softBlocked(true)
                    .softBlockerEventIds(ordered*.id)
                    .softBlockerReasons(ordered.collect { "soft: ${it.title} (${it.matchedRuleName})" })
                    .build()
            } else {
                segments << TimeSlot.builder()
                    .start(segStart)
                    .end(segEnd)
                    .windowName(slot.windowName)
                    .softBlocked(false)
                    .build()
            }
        }
        return segments
    }

    private static long sumMinutes(List<TimeSlot> slots) {
        if (!slots) {
            return 0L
        }
        long total = 0L
        slots.each { total += it.durationMinutes() }
        return total
    }

    /**
     * Merge adjacent reporting segments into contiguous placeable intervals for scheduling /
     * deadline-risk feasibility. Soft splits are diagnostic-only: placement may span free↔soft
     * continuously. Never merges across hard/managed gaps, non-adjacent intervals, or holes
     * outside working windows. Soft metadata is cleared on the result.
     */
    static List<TimeSlot> toPlaceableIntervals(List<TimeSlot> reportingSlots) {
        if (!reportingSlots) {
            return []
        }
        def sorted = reportingSlots.toSorted { a, b ->
            def c = a.start <=> b.start
            c != 0 ? c : a.end <=> b.end
        }
        List<TimeSlot> merged = []
        TimeSlot cur = sorted[0]
        for (int i = 1; i < sorted.size(); i++) {
            def next = sorted[i]
            // Adjacent only (touching end==start); never across gaps
            if (cur.end == next.start) {
                cur = TimeSlot.builder()
                    .start(cur.start)
                    .end(next.end)
                    .windowName(combineWindowNames(cur.windowName, next.windowName))
                    .softBlocked(false)
                    .build()
            } else {
                merged << asPlaceable(cur)
                cur = next
            }
        }
        merged << asPlaceable(cur)
        return merged
    }

    private static TimeSlot asPlaceable(TimeSlot slot) {
        if (!slot.softBlocked && !slot.softBlockerEventIds && !slot.softBlockerReasons) {
            return slot
        }
        return TimeSlot.builder()
            .start(slot.start)
            .end(slot.end)
            .windowName(slot.windowName)
            .softBlocked(false)
            .build()
    }

    static final class Interval {
        final Instant start
        final Instant end
        final CalendarEvent source

        Interval(Instant start, Instant end, CalendarEvent source) {
            this.start = start
            this.end = end
            this.source = source
        }
    }

    static final class AvailabilityResult {
        final List<TimeSlot> slots
        final List<PlanningExplanation> explanations
        final long usableCapacityMinutes
        final long softPenalizedMinutes
        final List<CalendarEvent> informationalEvents
        final List<CalendarEvent> managedOutputEvents
        final List<CalendarEvent> softBlockerEvents

        AvailabilityResult(List<TimeSlot> slots, List<PlanningExplanation> explanations,
                           long usableCapacityMinutes, long softPenalizedMinutes,
                           List<CalendarEvent> informationalEvents,
                           List<CalendarEvent> managedOutputEvents,
                           List<CalendarEvent> softBlockerEvents) {
            this.slots = Collections.unmodifiableList(new ArrayList<>(slots ?: []))
            this.explanations = Collections.unmodifiableList(new ArrayList<>(explanations ?: []))
            this.usableCapacityMinutes = usableCapacityMinutes
            this.softPenalizedMinutes = softPenalizedMinutes
            this.informationalEvents = Collections.unmodifiableList(new ArrayList<>(informationalEvents ?: []))
            this.managedOutputEvents = Collections.unmodifiableList(new ArrayList<>(managedOutputEvents ?: []))
            this.softBlockerEvents = Collections.unmodifiableList(new ArrayList<>(softBlockerEvents ?: []))
        }
    }
}
