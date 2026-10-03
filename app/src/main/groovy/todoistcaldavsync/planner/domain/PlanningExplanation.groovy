package todoistcaldavsync.planner.domain

import java.util.Collections

/**
 * Immutable explanation entry for capacity/plan diagnostics.
 */
final class PlanningExplanation {
    final String code
    final String message
    final String subjectType
    final String subjectId
    final Map<String, Object> details

    private PlanningExplanation(Builder b) {
        this.code = b.code
        this.message = b.message
        this.subjectType = b.subjectType
        this.subjectId = b.subjectId
        this.details = Collections.unmodifiableMap(new LinkedHashMap<>(b.details ?: [:]))
    }

    static Builder builder() {
        new Builder()
    }

    static PlanningExplanation of(String code, String message, String subjectType = null, String subjectId = null, Map details = [:]) {
        builder()
            .code(code)
            .message(message)
            .subjectType(subjectType)
            .subjectId(subjectId)
            .details(details ?: [:])
            .build()
    }

    static final class Builder {
        private String code
        private String message
        private String subjectType
        private String subjectId
        private Map<String, Object> details = [:]

        Builder code(String v) { this.code = v; this }
        Builder message(String v) { this.message = v; this }
        Builder subjectType(String v) { this.subjectType = v; this }
        Builder subjectId(String v) { this.subjectId = v; this }
        Builder details(Map<String, Object> v) { this.details = v ?: [:]; this }

        PlanningExplanation build() {
            if (!code) {
                throw new IllegalArgumentException('PlanningExplanation code is required')
            }
            if (!message) {
                throw new IllegalArgumentException('PlanningExplanation message is required')
            }
            return new PlanningExplanation(this)
        }
    }
}
