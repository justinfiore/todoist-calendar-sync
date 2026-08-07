package todoistcaldavsync.planner.domain

import java.time.Instant
import java.util.Objects

/**
 * Provider-neutral immutable hourly (or sub-range) weather sample.
 * Interval is half-open [start, end). Missing numeric fields are null (unknown).
 */
final class WeatherInterval {
    final Instant start
    final Instant end
    /** 0–100 precipitation probability, or null if unknown. */
    final Double precipitationProbability
    /** Precipitation amount in mm over the interval, or null if unknown. */
    final Double precipitationMm
    /** Weather condition code (provider-neutral integer when available). */
    final Integer weatherCode
    final String condition
    final Double temperatureC
    final Double windSpeedKph
    final Boolean daylight
    final Double confidence

    private WeatherInterval(Builder b) {
        this.start = b.start
        this.end = b.end
        this.precipitationProbability = b.precipitationProbability
        this.precipitationMm = b.precipitationMm
        this.weatherCode = b.weatherCode
        this.condition = b.condition
        this.temperatureC = b.temperatureC
        this.windSpeedKph = b.windSpeedKph
        this.daylight = b.daylight
        this.confidence = b.confidence
    }

    static Builder builder() {
        new Builder()
    }

    boolean overlaps(Instant rangeStart, Instant rangeEnd) {
        if (start == null || end == null || rangeStart == null || rangeEnd == null) {
            return false
        }
        return start.isBefore(rangeEnd) && rangeStart.isBefore(end)
    }

    @Override
    boolean equals(Object o) {
        if (this.is(o)) {
            return true
        }
        if (!(o instanceof WeatherInterval)) {
            return false
        }
        WeatherInterval other = (WeatherInterval) o
        return start == other.start &&
            end == other.end &&
            Objects.equals(precipitationProbability, other.precipitationProbability) &&
            Objects.equals(precipitationMm, other.precipitationMm) &&
            Objects.equals(weatherCode, other.weatherCode) &&
            Objects.equals(condition, other.condition) &&
            Objects.equals(temperatureC, other.temperatureC) &&
            Objects.equals(windSpeedKph, other.windSpeedKph) &&
            Objects.equals(daylight, other.daylight) &&
            Objects.equals(confidence, other.confidence)
    }

    @Override
    int hashCode() {
        return Objects.hash(start, end, precipitationProbability, precipitationMm, weatherCode,
            condition, temperatureC, windSpeedKph, daylight, confidence)
    }

    @Override
    String toString() {
        "WeatherInterval{[${start}, ${end}), precipProb=${precipitationProbability}}"
    }

    static final class Builder {
        private Instant start
        private Instant end
        private Double precipitationProbability
        private Double precipitationMm
        private Integer weatherCode
        private String condition
        private Double temperatureC
        private Double windSpeedKph
        private Boolean daylight
        private Double confidence

        Builder start(Instant v) { this.start = v; this }
        Builder end(Instant v) { this.end = v; this }
        Builder precipitationProbability(Double v) { this.precipitationProbability = v; this }
        Builder precipitationMm(Double v) { this.precipitationMm = v; this }
        Builder weatherCode(Integer v) { this.weatherCode = v; this }
        Builder condition(String v) { this.condition = v; this }
        Builder temperatureC(Double v) { this.temperatureC = v; this }
        Builder windSpeedKph(Double v) { this.windSpeedKph = v; this }
        Builder daylight(Boolean v) { this.daylight = v; this }
        Builder confidence(Double v) { this.confidence = v; this }

        WeatherInterval build() {
            if (start == null || end == null) {
                throw new IllegalArgumentException('WeatherInterval start and end are required')
            }
            if (!end.isAfter(start)) {
                throw new IllegalArgumentException("WeatherInterval end must be after start: [${start}, ${end})")
            }
            requireFinite('precipitationProbability', precipitationProbability)
            requireFinite('precipitationMm', precipitationMm)
            requireFinite('temperatureC', temperatureC)
            requireFinite('windSpeedKph', windSpeedKph)
            requireFinite('confidence', confidence)
            if (precipitationProbability != null &&
                (precipitationProbability < 0d || precipitationProbability > 100d)) {
                throw new IllegalArgumentException(
                    "precipitationProbability must be 0..100, got: ${precipitationProbability}")
            }
            if (precipitationMm != null && precipitationMm < 0d) {
                throw new IllegalArgumentException("precipitationMm must be non-negative, got: ${precipitationMm}")
            }
            if (windSpeedKph != null && windSpeedKph < 0d) {
                throw new IllegalArgumentException("windSpeedKph must be non-negative, got: ${windSpeedKph}")
            }
            if (confidence != null && (confidence < 0d || confidence > 1d)) {
                throw new IllegalArgumentException("confidence must be 0..1, got: ${confidence}")
            }
            return new WeatherInterval(this)
        }

        private static void requireFinite(String name, Double value) {
            if (value != null && !Double.isFinite(value)) {
                throw new IllegalArgumentException("${name} must be finite, got: ${value}")
            }
        }
    }
}
