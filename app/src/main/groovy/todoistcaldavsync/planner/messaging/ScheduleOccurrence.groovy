package todoistcaldavsync.planner.messaging

import todoistcaldavsync.planner.config.PlannerConfig

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/**
 * Deterministic schedule identity and occurrence keys for delivery idempotency.
 * Occurrence is anchored to scheduled local civil date/time (and cadence), not the
 * raw invocation instant or window minute. Same local date/time → one key across DST folds.
 */
final class ScheduleOccurrence {
    static final String MANUAL_SCHEDULE_ID = 'manual'
    static final String MANUAL_OCCURRENCE = 'manual'
    private static final DateTimeFormatter LOCAL_HM = DateTimeFormatter.ofPattern('HH:mm')

    private ScheduleOccurrence() {}

    /**
     * Stable schedule identity from canonical schedule fields (order-independent).
     * Does not include secrets or list index/name.
     */
    static String scheduleIdentity(PlannerConfig.MessageSchedule sched, String destination = '') {
        if (sched == null) {
            return MANUAL_SCHEDULE_ID
        }
        String kind = (sched.kind ?: '').trim().toLowerCase(Locale.ROOT)
        String expr = normalizeScheduleExpr(sched.schedule)
        String horizon = sched.horizon != null ? sched.horizon.toString() : ''
        String window = sched.window != null ? sched.window.toString() : ''
        String dest = (destination ?: '').trim()
        String raw = "sched|${kind}|${expr}|${horizon}|${window}|${dest}"
        return 'sid-' + sha256Hex(raw).substring(0, 16)
    }

    /**
     * Occurrence key for the schedule period containing {@code now} in {@code zone}.
     * Daily / every-day HH:mm: local scheduled date + time.
     * Weekly (dow in expression): that week's scheduled local date + time.
     * DST gap: effective scheduled local time is first valid civil time after the gap;
     * occurrence still maps to one key for that local calendar date.
     * DST fold: key uses local civil date/time only (no offset), so both fold instants share one key.
     */
    static String occurrenceKey(PlannerConfig.MessageSchedule sched, Instant now, ZoneId zone) {
        if (sched == null || now == null || zone == null) {
            return MANUAL_OCCURRENCE
        }
        ParsedSchedule parsed = parseSchedule(sched.schedule)
        if (parsed == null) {
            return MANUAL_OCCURRENCE
        }
        ZonedDateTime zdt = now.atZone(zone)
        LocalDate date = zdt.toLocalDate()
        if (parsed.dayOfWeek != null && zdt.dayOfWeek != parsed.dayOfWeek) {
            date = date.with(TemporalAdjusters.previousOrSame(parsed.dayOfWeek))
        }
        LocalTime effective = effectiveLocalTime(date, parsed.time, zone)
        return occurrenceLabel(date, effective, parsed.dayOfWeek != null)
    }

    /**
     * Whether schedule is due at {@code now}. DST gap: uses first valid local time after gap
     * as the effective target so the occurrence is reachable once. DST fold: local wall-time
     * window match only (second-of-day), so each local occurrence is due at most once per key.
     */
    static boolean isScheduleDue(PlannerConfig.MessageSchedule sched, Instant now, ZoneId zone) {
        if (sched == null || !sched.schedule || now == null || zone == null) {
            return false
        }
        ParsedSchedule parsed = parseSchedule(sched.schedule)
        if (parsed == null) {
            throw new IllegalArgumentException("Unsupported schedule expression: ${sched.schedule}")
        }
        Duration window = sched.window ?: Duration.ofMinutes(30)
        ZonedDateTime zdt = now.atZone(zone)
        if (parsed.dayOfWeek != null && zdt.dayOfWeek != parsed.dayOfWeek) {
            return false
        }
        LocalTime effective = effectiveLocalTime(zdt.toLocalDate(), parsed.time, zone)
        return withinWindow(zdt.toLocalTime(), effective, window)
    }

    /**
     * Effective scheduled local time on {@code date}. If configured time falls in a DST spring
     * gap, returns the first valid local time at/after the gap (transition's dateTimeAfter).
     * Fold (ambiguous) times are left as configured local wall time.
     */
    static LocalTime effectiveLocalTime(LocalDate date, LocalTime configured, ZoneId zone) {
        if (date == null || configured == null || zone == null) {
            return configured
        }
        LocalDateTime ldt = LocalDateTime.of(date, configured)
        List<ZoneOffset> valid = zone.rules.getValidOffsets(ldt)
        if (valid != null && !valid.isEmpty()) {
            return configured
        }
        // Gap: first valid civil time after the gap on this local date
        def transition = zone.rules.nextTransition(date.atStartOfDay(zone).toInstant().minusSeconds(1))
        while (transition != null) {
            if (transition.gap) {
                LocalDateTime after = transition.dateTimeAfter
                LocalDateTime before = transition.dateTimeBefore
                // configured LDT is nonexistent when it lies in the gap span
                if (!ldt.isBefore(before) && ldt.isBefore(after)) {
                    return after.toLocalTime()
                }
                // also: getValidOffsets empty implies gap covering ldt; use after if same date
                if (after.toLocalDate() == date && !after.toLocalTime().isBefore(configured)) {
                    return after.toLocalTime()
                }
            }
            Instant next = transition.instant.plusSeconds(1)
            def nxt = zone.rules.nextTransition(next)
            if (nxt == null || !nxt.instant.isAfter(transition.instant)) {
                break
            }
            if (nxt.dateTimeBefore.toLocalDate().isAfter(date)) {
                break
            }
            transition = nxt
        }
        // Minute scan fallback (bounded)
        LocalTime t = configured
        for (int i = 0; i < 180; i++) {
            List<ZoneOffset> offs = zone.rules.getValidOffsets(LocalDateTime.of(date, t))
            if (offs != null && !offs.isEmpty()) {
                return t
            }
            LocalTime next = t.plusMinutes(1)
            if (next == t || (next == LocalTime.MIDNIGHT && i > 0)) {
                break
            }
            t = next
        }
        return configured
    }

    static String occurrenceLabel(LocalDate date, LocalTime time, boolean weekly) {
        String base = "${date}|${time.format(LOCAL_HM)}"
        return weekly ? "w|${base}" : "d|${base}"
    }

    static String normalizeScheduleExpr(String expr) {
        if (!expr) {
            return ''
        }
        return expr.trim().toLowerCase(Locale.ROOT).replaceAll(/\s+/, ' ')
    }

    static ParsedSchedule parseSchedule(String expr) {
        if (!expr) {
            return null
        }
        String s = expr.trim()
        def hm = s =~ /^(\d{1,2}):(\d{2})$/
        if (hm.matches()) {
            return new ParsedSchedule(LocalTime.of(hm[0][1] as int, hm[0][2] as int), null)
        }
        def dhm = s =~ /^(?i)(mon|tue|wed|thu|fri|sat|sun|monday|tuesday|wednesday|thursday|friday|saturday|sunday)\s+(\d{1,2}):(\d{2})$/
        if (dhm.matches()) {
            return new ParsedSchedule(
                LocalTime.of(dhm[0][2] as int, dhm[0][3] as int),
                parseDow(dhm[0][1].toString()))
        }
        def cron = s =~ /^(\d{1,2})\s+(\d{1,2})\s+\*\s+\*\s+(\*|\d|mon|tue|wed|thu|fri|sat|sun)$/
        if (cron.matches()) {
            int minute = cron[0][1] as int
            int hour = cron[0][2] as int
            String dowPart = cron[0][3].toString().toLowerCase(Locale.ROOT)
            DayOfWeek dow = null
            if (dowPart != '*') {
                dow = dowPart.matches(/\d/) ?
                    DayOfWeek.of(((dowPart as int) == 0 ? 7 : dowPart as int)) :
                    parseDow(dowPart)
            }
            return new ParsedSchedule(LocalTime.of(hour, minute), dow)
        }
        return null
    }

    private static boolean withinWindow(LocalTime now, LocalTime target, Duration window) {
        int nowSec = now.toSecondOfDay()
        int tSec = target.toSecondOfDay()
        int w = (int) Math.min(Integer.MAX_VALUE, window.seconds)
        if (nowSec < tSec) {
            return false
        }
        return (nowSec - tSec) < w
    }

    private static DayOfWeek parseDow(String s) {
        String x = s.toLowerCase(Locale.ROOT)
        if (x.startsWith('mon')) return DayOfWeek.MONDAY
        if (x.startsWith('tue')) return DayOfWeek.TUESDAY
        if (x.startsWith('wed')) return DayOfWeek.WEDNESDAY
        if (x.startsWith('thu')) return DayOfWeek.THURSDAY
        if (x.startsWith('fri')) return DayOfWeek.FRIDAY
        if (x.startsWith('sat')) return DayOfWeek.SATURDAY
        if (x.startsWith('sun')) return DayOfWeek.SUNDAY
        throw new IllegalArgumentException("Unknown day: ${s}")
    }

    private static String sha256Hex(String raw) {
        MessageDigest md = MessageDigest.getInstance('SHA-256')
        byte[] dig = md.digest(raw.getBytes(StandardCharsets.UTF_8))
        return dig.collect { String.format('%02x', it & 0xff) }.join()
    }

    static final class ParsedSchedule {
        final LocalTime time
        final DayOfWeek dayOfWeek

        ParsedSchedule(LocalTime time, DayOfWeek dayOfWeek) {
            this.time = time
            this.dayOfWeek = dayOfWeek
        }
    }
}
