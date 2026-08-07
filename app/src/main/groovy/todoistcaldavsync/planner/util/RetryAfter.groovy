package todoistcaldavsync.planner.util

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

/**
 * Parse HTTP Retry-After: delta-seconds or RFC 7231 HTTP-date.
 * Deterministic; accepts {@code now} for date form. Past → 0; excessive → max; malformed → null.
 */
final class RetryAfter {
    /** Default clamp for excessive wait (24h). */
    static final long DEFAULT_MAX_SECONDS = 86_400L

    private static final DateTimeFormatter RFC1123 =
        DateTimeFormatter.RFC_1123_DATE_TIME.withLocale(Locale.US)
    private static final DateTimeFormatter RFC850 =
        DateTimeFormatter.ofPattern("EEEE, dd-MMM-yy HH:mm:ss zzz", Locale.US)
    private static final DateTimeFormatter ASCTIME =
        DateTimeFormatter.ofPattern("EEE MMM ppd HH:mm:ss yyyy", Locale.US)
            .withZone(ZoneOffset.UTC)

    private RetryAfter() {}

    /**
     * Extract Retry-After from header map (case-insensitive name) and parse.
     */
    static Long parseSeconds(Map<String, ? extends Object> headers,
                             Instant now = Instant.now(),
                             long maxSeconds = DEFAULT_MAX_SECONDS) {
        if (headers == null) {
            return null
        }
        def entry = headers.find { k, v -> k != null && k.toString().equalsIgnoreCase('Retry-After') }
        if (entry == null || entry.value == null) {
            return null
        }
        Object val = entry.value
        String raw
        if (val instanceof Collection) {
            def first = (val as Collection).find { it != null }
            raw = first?.toString()
        } else {
            raw = val.toString()
        }
        return parse(raw, now, maxSeconds)
    }

    /**
     * Parse a single Retry-After field value.
     * @return seconds to wait (clamped), or null if malformed/blank
     */
    static Long parse(String raw, Instant now = Instant.now(), long maxSeconds = DEFAULT_MAX_SECONDS) {
        if (raw == null) {
            return null
        }
        String s = raw.trim()
        if (s.isEmpty()) {
            return null
        }
        Instant clock = now != null ? now : Instant.now()
        long max = maxSeconds > 0L ? maxSeconds : DEFAULT_MAX_SECONDS

        // delta-seconds
        if (s.matches(/^\d+$/)) {
            try {
                long v = Long.parseLong(s)
                if (v < 0L) {
                    return 0L
                }
                return Math.min(v, max)
            } catch (Exception ignored) {
                return null
            }
        }

        Instant when = parseHttpDate(s)
        if (when == null) {
            return null
        }
        long delta = when.epochSecond - clock.epochSecond
        if (delta < 0L) {
            return 0L
        }
        return Math.min(delta, max)
    }

    static Instant parseHttpDate(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return null
        }
        String s = raw.trim()
        // IMF-fixdate / RFC 1123
        try {
            return Instant.from(RFC1123.parse(s))
        } catch (DateTimeParseException ignored) {
        }
        // RFC 850
        try {
            return Instant.from(RFC850.parse(s))
        } catch (DateTimeParseException ignored) {
        }
        // asctime
        try {
            return Instant.from(ASCTIME.parse(s))
        } catch (DateTimeParseException ignored) {
        }
        return null
    }
}
