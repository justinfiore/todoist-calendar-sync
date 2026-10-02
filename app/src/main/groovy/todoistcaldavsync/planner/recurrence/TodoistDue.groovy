package todoistcaldavsync.planner.recurrence

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Collections

/** Todoist-owned Due value. Recurrence text is deliberately opaque. */
final class TodoistDue {
    final String date
    final String string
    final boolean recurring
    final String lang
    final String timezone
    final Instant instant
    final boolean allDay

    private TodoistDue(String date, String string, boolean recurring, String lang,
                       String timezone, Instant instant, boolean allDay) {
        this.date = date
        this.string = string
        this.recurring = recurring
        this.lang = lang
        this.timezone = timezone
        this.instant = instant
        this.allDay = allDay
    }

    static TodoistDue from(Object value, ZoneId plannerZone,
                           Closure<Instant> instantParser) {
        if (value == null) return null
        if (!(value instanceof Map)) {
            String date = value.toString()
            return new TodoistDue(date, null, false, null, null,
                instantParser.call(date, plannerZone), !date.contains('T'))
        }
        Map raw = value as Map
        String date = raw.date?.toString()
        boolean recurring = raw.is_recurring == true || raw.isRecurring == true
        if (!date) {
            if (recurring) throw new IllegalArgumentException('Todoist Due date is required')
            return null
        }
        String expression = raw.string?.toString()
        String lang = raw.lang?.toString()
        String timezone = (raw.timezone ?: raw.time_zone ?: raw.timeZone)?.toString()
        if (recurring && (!expression || !lang || !raw.containsKey('timezone') &&
            !raw.containsKey('time_zone') && !raw.containsKey('timeZone'))) {
            throw new IllegalArgumentException(
                'Recurring Todoist Due requires date, string, is_recurring, lang, and timezone')
        }
        ZoneId zone = plannerZone
        if (timezone && date.contains('T') && !hasOffset(date)) {
            try { zone = ZoneId.of(timezone) }
            catch (Exception e) { throw new IllegalArgumentException("Invalid due timezone '${timezone}'", e) }
        }
        new TodoistDue(date, expression, recurring, lang, timezone,
            instantParser.call(date, zone), !date.contains('T'))
    }

    boolean completeRecurrenceTuple() {
        !recurring || (date && string && lang && timezone != null)
    }

    Map<String, Object> syncTuple(String replacementDate = date) {
        if (!recurring || !completeRecurrenceTuple()) {
            throw new IllegalStateException('A complete recurring Due tuple is required')
        }
        Collections.unmodifiableMap(new LinkedHashMap<String, Object>([
            date: replacementDate,
            string: string,
            is_recurring: true,
            lang: lang,
            timezone: timezone
        ]))
    }

    String recurrenceFingerprintInput() {
        recurring ? [string, lang, timezone].collect { it == null ? '<null>' : it }.join('\u0000') : ''
    }

    LocalDate localDate(ZoneId plannerZone) {
        if (date ==~ /\d{4}-\d{2}-\d{2}/) return LocalDate.parse(date)
        ZoneId zone = timezone ? ZoneId.of(timezone) : plannerZone
        instant.atZone(zone).toLocalDate()
    }

    private static boolean hasOffset(String value) {
        value.endsWith('Z') || value.endsWith('z') || value ==~ /.*[+-]\d{2}:?\d{2}$/
    }
}
