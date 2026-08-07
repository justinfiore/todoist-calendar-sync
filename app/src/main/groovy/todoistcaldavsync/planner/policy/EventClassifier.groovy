package todoistcaldavsync.planner.policy

import todoistcaldavsync.planner.config.PlannerConfig
import todoistcaldavsync.planner.domain.CalendarEvent
import todoistcaldavsync.planner.domain.EventRole
import todoistcaldavsync.planner.domain.PlanningExplanation

/**
 * Classifies calendar events using ordered explicit rules, then calendar defaults,
 * then a visible safe fallback. Unknown calendars are diagnostic, not silently free.
 */
class EventClassifier {
    private final PlannerConfig config

    EventClassifier(PlannerConfig config) {
        if (config == null) {
            throw new IllegalArgumentException('PlannerConfig is required')
        }
        this.config = config
    }

    CalendarEvent classify(CalendarEvent event) {
        if (event == null) {
            throw new IllegalArgumentException('event must not be null')
        }

        // 1. First ordered explicit event rule
        for (PlannerConfig.EventRule rule : config.eventRules) {
            if (rule.matches(event.calendarName, event.title, event.description)) {
                return event.withClassification(
                    rule.role,
                    rule.name,
                    "Matched explicit event rule '${rule.name}' → ${rule.role.configValue}",
                    rule.bufferBeforeMinutes,
                    rule.bufferAfterMinutes,
                    false
                )
            }
        }

        // 2. Calendar default
        def calDefault = config.findCalendarDefault(event.calendarName)
        if (calDefault != null) {
            return event.withClassification(
                calDefault.defaultRole,
                "calendar_default:${calDefault.calendarName}",
                "Matched calendar default for '${calDefault.calendarName}' → ${calDefault.defaultRole.configValue}",
                0,
                0,
                false
            )
        }

        // 3. Visible safe fallback — unknown calendar is diagnostic
        return event.withClassification(
            config.unknownCalendarFallback,
            'unknown_calendar_fallback',
            "Unknown calendar '${event.calendarName}' — applied safe fallback role ${config.unknownCalendarFallback.configValue} (not treated as free time silently)",
            0,
            0,
            true
        )
    }

    List<CalendarEvent> classifyAll(List<CalendarEvent> events) {
        (events ?: []).collect { classify(it) }
    }

    List<PlanningExplanation> explanationsFor(List<CalendarEvent> classified) {
        (classified ?: []).collect { ev ->
            PlanningExplanation.of(
                ev.unknownCalendar ? 'unknown_calendar' : 'event_classification',
                ev.classificationReason ?: 'Unclassified',
                'event',
                ev.id,
                [
                    calendarName   : ev.calendarName,
                    title          : ev.title,
                    role           : ev.role?.configValue,
                    matchedRuleName: ev.matchedRuleName,
                    bufferBefore   : ev.bufferBeforeMinutes,
                    bufferAfter    : ev.bufferAfterMinutes,
                    unknownCalendar: ev.unknownCalendar
                ]
            )
        }
    }
}
