package todoistcaldavsync.planner.domain

import java.time.Instant
import java.util.Collections
import java.util.Objects

/**
 * Structured deterministic result of evaluating a task interval against weather policy.
 */
final class WeatherEvaluation {
    static final String RESULT_FEASIBLE = 'FEASIBLE'
    static final String RESULT_INFEASIBLE = 'INFEASIBLE'
    static final String RESULT_UNKNOWN = 'UNKNOWN'
    static final String RESULT_STALE = 'STALE'
    static final String RESULT_NOT_APPLICABLE = 'NOT_APPLICABLE'

    final String result
    final boolean hardInfeasible
    final long scoreDelta
    final String ruleId
    final String ruleName
    final String reason
    final String provider
    final Instant forecastIssuedAt
    final Instant forecastRetrievedAt
    final Double latitude
    final Double longitude
    final String observedField
    final Object observedValue
    final Object threshold
    final List<Instant> relevantHours
    final boolean alternativesSignal
    final Map<String, Object> details

    private WeatherEvaluation(Builder b) {
        this.result = b.result
        this.hardInfeasible = b.hardInfeasible
        this.scoreDelta = b.scoreDelta
        this.ruleId = b.ruleId
        this.ruleName = b.ruleName
        this.reason = b.reason
        this.provider = b.provider
        this.forecastIssuedAt = b.forecastIssuedAt
        this.forecastRetrievedAt = b.forecastRetrievedAt
        this.latitude = b.latitude
        this.longitude = b.longitude
        this.observedField = b.observedField
        this.observedValue = b.observedValue
        this.threshold = b.threshold
        this.relevantHours = Collections.unmodifiableList(new ArrayList<>(b.relevantHours ?: []))
        this.alternativesSignal = b.alternativesSignal
        this.details = Collections.unmodifiableMap(new LinkedHashMap<>(b.details ?: [:]))
    }

    static Builder builder() {
        new Builder()
    }

    @Override
    boolean equals(Object o) {
        if (this.is(o)) {
            return true
        }
        if (!(o instanceof WeatherEvaluation)) {
            return false
        }
        WeatherEvaluation other = (WeatherEvaluation) o
        return hardInfeasible == other.hardInfeasible &&
            scoreDelta == other.scoreDelta &&
            alternativesSignal == other.alternativesSignal &&
            Objects.equals(result, other.result) &&
            Objects.equals(ruleId, other.ruleId) &&
            Objects.equals(ruleName, other.ruleName) &&
            Objects.equals(reason, other.reason) &&
            Objects.equals(provider, other.provider) &&
            Objects.equals(forecastIssuedAt, other.forecastIssuedAt) &&
            Objects.equals(forecastRetrievedAt, other.forecastRetrievedAt) &&
            Objects.equals(latitude, other.latitude) &&
            Objects.equals(longitude, other.longitude) &&
            Objects.equals(observedField, other.observedField) &&
            Objects.equals(observedValue, other.observedValue) &&
            Objects.equals(threshold, other.threshold) &&
            Objects.equals(relevantHours, other.relevantHours) &&
            Objects.equals(details, other.details)
    }

    @Override
    int hashCode() {
        return Objects.hash(result, hardInfeasible, scoreDelta, ruleId, ruleName, reason, provider,
            forecastIssuedAt, forecastRetrievedAt, latitude, longitude, observedField, observedValue,
            threshold, relevantHours, alternativesSignal, details)
    }

    @Override
    String toString() {
        "WeatherEvaluation{result=${result}, hardInfeasible=${hardInfeasible}, rule=${ruleName}}"
    }

    static WeatherEvaluation notApplicable(String reason = 'Task does not match any weather rule') {
        builder()
            .result(RESULT_NOT_APPLICABLE)
            .hardInfeasible(false)
            .scoreDelta(0L)
            .reason(reason)
            .build()
    }

    Map<String, Object> toExplanationDetails() {
        Map<String, Object> m = new LinkedHashMap<>()
        m.result = result
        if (ruleId) m.ruleId = ruleId
        if (ruleName) m.ruleName = ruleName
        if (provider) m.provider = provider
        if (forecastIssuedAt) m.forecastIssuedAt = forecastIssuedAt.toString()
        if (forecastRetrievedAt) m.forecastRetrievedAt = forecastRetrievedAt.toString()
        if (latitude != null) m.latitude = latitude
        if (longitude != null) m.longitude = longitude
        if (observedField) m.observedField = observedField
        if (observedValue != null) m.observedValue = observedValue
        if (threshold != null) m.threshold = threshold
        if (relevantHours) m.relevantHours = relevantHours.collect { it.toString() }
        m.alternativesSignal = alternativesSignal
        m.scoreDelta = scoreDelta
        if (details) m.putAll(details)
        return m
    }

    static final class Builder {
        private String result = RESULT_NOT_APPLICABLE
        private boolean hardInfeasible
        private long scoreDelta
        private String ruleId
        private String ruleName
        private String reason
        private String provider
        private Instant forecastIssuedAt
        private Instant forecastRetrievedAt
        private Double latitude
        private Double longitude
        private String observedField
        private Object observedValue
        private Object threshold
        private List<Instant> relevantHours = []
        private boolean alternativesSignal
        private Map<String, Object> details = [:]

        Builder result(String v) { this.result = v; this }
        Builder hardInfeasible(boolean v) { this.hardInfeasible = v; this }
        Builder scoreDelta(long v) { this.scoreDelta = v; this }
        Builder ruleId(String v) { this.ruleId = v; this }
        Builder ruleName(String v) { this.ruleName = v; this }
        Builder reason(String v) { this.reason = v; this }
        Builder provider(String v) { this.provider = v; this }
        Builder forecastIssuedAt(Instant v) { this.forecastIssuedAt = v; this }
        Builder forecastRetrievedAt(Instant v) { this.forecastRetrievedAt = v; this }
        Builder latitude(Double v) { this.latitude = v; this }
        Builder longitude(Double v) { this.longitude = v; this }
        Builder observedField(String v) { this.observedField = v; this }
        Builder observedValue(Object v) { this.observedValue = v; this }
        Builder threshold(Object v) { this.threshold = v; this }
        Builder relevantHours(List<Instant> v) { this.relevantHours = v ?: []; this }
        Builder alternativesSignal(boolean v) { this.alternativesSignal = v; this }
        Builder details(Map<String, Object> v) { this.details = v ?: [:]; this }

        WeatherEvaluation build() {
            if (!result) {
                throw new IllegalArgumentException('WeatherEvaluation result is required')
            }
            if (!reason) {
                reason = result
            }
            return new WeatherEvaluation(this)
        }
    }
}
