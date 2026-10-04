package todoistcaldavsync.planner.recurrence

import groovy.json.JsonOutput
import groovy.json.JsonSlurper

/** Exact, suffix-anchored codec that never rewrites the human description prefix. */
final class LifecycleMarkerCodec {
    static final String DIVIDER = '---'
    static final String WARNING = '**SmartPlanner metadata — do not edit**'
    static final String PREFIX = "\n\n${DIVIDER}\n\n${WARNING}\n\n```json\n"
    static final String SUFFIX = '\n```'

    MarkerRead read(String description) {
        String text = description ?: ''
        int first = text.indexOf(PREFIX)
        if (first < 0) {
            return text.contains(WARNING) || text.contains('"owner":"smartplanner"') ?
                MarkerRead.unsafe(text, 'relocated_or_malformed') : MarkerRead.absent(text)
        }
        int second = text.indexOf(PREFIX, first + PREFIX.length())
        if (second >= 0) return MarkerRead.unsafe(text, 'duplicate')
        if (!text.endsWith(SUFFIX)) return MarkerRead.unsafe(text, 'not_suffix')
        String json = text.substring(first + PREFIX.length(), text.length() - SUFFIX.length())
        if (json.contains('\n') || json.contains('\r')) return MarkerRead.unsafe(text, 'non_canonical')
        try {
            Object parsed = new JsonSlurper().parseText(json)
            if (!(parsed instanceof Map)) return MarkerRead.unsafe(text, 'malformed')
            Map m = parsed as Map
            if (m.schema_version != LifecycleMarker.VERSION || m.owner != 'smartplanner') {
                return MarkerRead.unsupported(text, m.schema_version)
            }
            LifecycleMarker marker = new LifecycleMarker([
                taskId: m.task_id, completedCount: m.completed_count,
                deadlineMode: m.deadline_mode, deadlineSource: m.deadline_source,
                deadlineDate: m.deadline_date, pendingLegacySourceDate: m.pending_legacy_source_date,
                lastVerifiedDue: m.last_verified_due, recurrenceFingerprint: m.recurrence_fingerprint,
                lastPlannerDue: m.last_planner_due, markerGeneration: m.marker_generation,
                lastCommandId: m.last_command_id
            ])
            if (JsonOutput.toJson(marker.canonicalMap()) != json) {
                return MarkerRead.unsafe(text, 'non_canonical')
            }
            return MarkerRead.valid(text.substring(0, first), marker)
        } catch (Exception ignored) {
            return MarkerRead.unsafe(text, 'malformed')
        }
    }

    String merge(String description, LifecycleMarker marker) {
        MarkerRead current = read(description)
        if (current.status == MarkerStatus.UNSAFE || current.status == MarkerStatus.UNSUPPORTED) {
            throw new IllegalStateException("Unsafe lifecycle marker: ${current.reason}")
        }
        String humanPrefix = current.status == MarkerStatus.VALID ? current.humanPrefix : (description ?: '')
        humanPrefix + PREFIX + JsonOutput.toJson(marker.canonicalMap()) + SUFFIX
    }
}

enum MarkerStatus { ABSENT, VALID, UNSAFE, UNSUPPORTED }

final class MarkerRead {
    final MarkerStatus status
    final String humanPrefix
    final LifecycleMarker marker
    final String reason
    final Object version

    private MarkerRead(MarkerStatus status, String humanPrefix, LifecycleMarker marker,
                       String reason, Object version = null) {
        this.status = status; this.humanPrefix = humanPrefix; this.marker = marker
        this.reason = reason; this.version = version
    }

    static MarkerRead absent(String text) { new MarkerRead(MarkerStatus.ABSENT, text, null, null) }
    static MarkerRead valid(String prefix, LifecycleMarker marker) { new MarkerRead(MarkerStatus.VALID, prefix, marker, null) }
    static MarkerRead unsafe(String text, String reason) { new MarkerRead(MarkerStatus.UNSAFE, text, null, reason) }
    static MarkerRead unsupported(String text, Object version) {
        new MarkerRead(MarkerStatus.UNSUPPORTED, text, null, 'unsupported_version', version)
    }
}
