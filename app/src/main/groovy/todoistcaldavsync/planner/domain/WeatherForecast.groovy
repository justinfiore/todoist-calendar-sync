package todoistcaldavsync.planner.domain

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Collections

/**
 * Provider-neutral immutable weather forecast snapshot.
 * Times are absolute instants; location timezone is retained for daylight/civil interpretation.
 */
final class WeatherForecast {
    final String provider
    final Instant issuedAt
    final Instant retrievedAt
    final double latitude
    final double longitude
    final ZoneId timezone
    final List<WeatherInterval> intervals
    final Map<LocalDate, DaylightWindow> daylightByDate
    final Map<String, Object> metadata

    private WeatherForecast(Builder b) {
        this.provider = b.provider
        this.issuedAt = b.issuedAt
        this.retrievedAt = b.retrievedAt
        this.latitude = b.latitude
        this.longitude = b.longitude
        this.timezone = b.timezone
        this.intervals = Collections.unmodifiableList(new ArrayList<>(b.intervals ?: []))
        this.daylightByDate = Collections.unmodifiableMap(new LinkedHashMap<>(b.daylightByDate ?: [:]))
        this.metadata = Collections.unmodifiableMap(new LinkedHashMap<>(b.metadata ?: [:]))
    }

    @Override
    boolean equals(Object o) {
        if (this.is(o)) {
            return true
        }
        if (!(o instanceof WeatherForecast)) {
            return false
        }
        WeatherForecast other = (WeatherForecast) o
        return provider == other.provider &&
            issuedAt == other.issuedAt &&
            retrievedAt == other.retrievedAt &&
            Double.compare(latitude, other.latitude) == 0 &&
            Double.compare(longitude, other.longitude) == 0 &&
            timezone == other.timezone &&
            intervals == other.intervals &&
            daylightByDate == other.daylightByDate &&
            metadata == other.metadata
    }

    @Override
    int hashCode() {
        int result = provider != null ? provider.hashCode() : 0
        result = 31 * result + (issuedAt != null ? issuedAt.hashCode() : 0)
        result = 31 * result + (retrievedAt != null ? retrievedAt.hashCode() : 0)
        result = 31 * result + Double.hashCode(latitude)
        result = 31 * result + Double.hashCode(longitude)
        result = 31 * result + (timezone != null ? timezone.hashCode() : 0)
        result = 31 * result + (intervals != null ? intervals.hashCode() : 0)
        result = 31 * result + (daylightByDate != null ? daylightByDate.hashCode() : 0)
        result = 31 * result + (metadata != null ? metadata.hashCode() : 0)
        return result
    }

    @Override
    String toString() {
        "WeatherForecast{provider=${provider}, issuedAt=${issuedAt}, lat=${latitude}, lon=${longitude}, intervals=${intervals?.size()}}"
    }

    static Builder builder() {
        new Builder()
    }

    /**
     * Intervals that overlap [start, end) in deterministic start order.
     */
    List<WeatherInterval> intervalsOverlapping(Instant start, Instant end) {
        if (start == null || end == null || !end.isAfter(start)) {
            return []
        }
        return intervals.findAll { it.overlaps(start, end) }
            .toSorted { a, b -> a.start <=> b.start ?: a.end <=> b.end }
    }

    DaylightWindow daylightFor(LocalDate date) {
        date == null ? null : daylightByDate[date]
    }

    boolean isStale(Instant now, java.time.Duration maxAge) {
        if (now == null || maxAge == null || issuedAt == null) {
            return false
        }
        return issuedAt.isBefore(now - maxAge)
    }

        static final class DaylightWindow {
        final LocalDate date
        final Instant sunrise
        final Instant sunset

        DaylightWindow(LocalDate date, Instant sunrise, Instant sunset) {
            this.date = date
            this.sunrise = sunrise
            this.sunset = sunset
        }

        /**
         * True when [start, end) is entirely within [sunrise, sunset] (inclusive sunrise, exclusive sunset).
         */
        boolean fullyContains(Instant start, Instant end) {
            if (sunrise == null || sunset == null || start == null || end == null) {
                return false
            }
            return !start.isBefore(sunrise) && !end.isAfter(sunset)
        }

        @Override
        boolean equals(Object o) {
            if (this.is(o)) {
                return true
            }
            if (!(o instanceof DaylightWindow)) {
                return false
            }
            DaylightWindow other = (DaylightWindow) o
            return date == other.date && sunrise == other.sunrise && sunset == other.sunset
        }

        @Override
        int hashCode() {
            int result = date != null ? date.hashCode() : 0
            result = 31 * result + (sunrise != null ? sunrise.hashCode() : 0)
            result = 31 * result + (sunset != null ? sunset.hashCode() : 0)
            return result
        }
    }

    static final class Builder {
        private String provider
        private Instant issuedAt
        private Instant retrievedAt
        private double latitude
        private double longitude
        private ZoneId timezone = ZoneId.of('UTC')
        private List<WeatherInterval> intervals = []
        private Map<LocalDate, DaylightWindow> daylightByDate = new LinkedHashMap<>()
        private Map<String, Object> metadata = [:]

        Builder provider(String v) { this.provider = v; this }
        Builder issuedAt(Instant v) { this.issuedAt = v; this }
        Builder retrievedAt(Instant v) { this.retrievedAt = v; this }
        Builder latitude(double v) { this.latitude = v; this }
        Builder longitude(double v) { this.longitude = v; this }
        Builder timezone(ZoneId v) { this.timezone = v ?: ZoneId.of('UTC'); this }
        Builder intervals(List<WeatherInterval> v) {
            this.intervals = v != null ? new ArrayList<>(v) : []
            this
        }
        Builder daylightByDate(Map<LocalDate, DaylightWindow> v) {
            this.daylightByDate = v ? new LinkedHashMap<>(v) : new LinkedHashMap<>()
            this
        }
        Builder metadata(Map<String, Object> v) { this.metadata = v ?: [:]; this }

        WeatherForecast build() {
            if (!provider) {
                throw new IllegalArgumentException('WeatherForecast provider is required')
            }
            if (issuedAt == null) {
                throw new IllegalArgumentException('WeatherForecast issuedAt is required')
            }
            if (retrievedAt == null) {
                retrievedAt = issuedAt
            }
            if (timezone == null) {
                throw new IllegalArgumentException('WeatherForecast timezone is required')
            }
            if (!Double.isFinite(latitude)) {
                throw new IllegalArgumentException("WeatherForecast latitude must be finite, got: ${latitude}")
            }
            if (!Double.isFinite(longitude)) {
                throw new IllegalArgumentException("WeatherForecast longitude must be finite, got: ${longitude}")
            }
            if (latitude < -90d || latitude > 90d) {
                throw new IllegalArgumentException("WeatherForecast latitude out of range: ${latitude}")
            }
            if (longitude < -180d || longitude > 180d) {
                throw new IllegalArgumentException("WeatherForecast longitude out of range: ${longitude}")
            }
            this.intervals = normalizeStrictIntervals(this.intervals)
            return new WeatherForecast(this)
        }

        /**
         * Sorted copy; reject nulls, non-positive duration (via interval), duplicate starts, and overlaps.
         * Abutting intervals (end == next.start) are allowed.
         */
        private static List<WeatherInterval> normalizeStrictIntervals(List<WeatherInterval> raw) {
            List<WeatherInterval> list = raw ?: []
            list.eachWithIndex { WeatherInterval iv, int idx ->
                if (iv == null) {
                    throw new IllegalArgumentException("WeatherForecast intervals[${idx}] must not be null")
                }
            }
            List<WeatherInterval> sorted = list.toSorted { a, b -> a.start <=> b.start ?: a.end <=> b.end }
            for (int i = 0; i < sorted.size(); i++) {
                WeatherInterval iv = sorted[i]
                if (!iv.end.isAfter(iv.start)) {
                    throw new IllegalArgumentException(
                        "WeatherForecast intervals[${i}] must have positive duration: [${iv.start}, ${iv.end})")
                }
                if (i > 0) {
                    WeatherInterval prev = sorted[i - 1]
                    if (iv.start == prev.start) {
                        throw new IllegalArgumentException(
                            "WeatherForecast intervals must not have duplicate starts: ${iv.start}")
                    }
                    if (iv.start.isBefore(prev.end)) {
                        throw new IllegalArgumentException(
                            "WeatherForecast intervals must not overlap: [${prev.start}, ${prev.end}) and [${iv.start}, ${iv.end})")
                    }
                }
            }
            return sorted
        }
    }
}
