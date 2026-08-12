package todoistcaldavsync.planner.domain

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.regex.Pattern

/**
 * Deterministic planner-owned event identity and ownership markers.
 * Only events with these UIDs/markers on the configured managed calendar may be mutated.
 */
final class ManagedEventIds {
    static final String UID_PREFIX = 'planner-'
    static final String UID_DOMAIN = 'todoist-planner.local'
    /** Marker embedded in description for ownership checks independent of UID format. */
    static final String OWNERSHIP_MARKER = 'X-TODOIST-PLANNER-MANAGED:1'
    static final String BLOCK_ID_PREFIX = 'block-id:'
    static final String PLAN_ID_PREFIX = 'plan-id:'

    private ManagedEventIds() {}

    /**
     * Deterministic UID for a scheduled block. Stable across reruns of the same block id.
     */
    static String uidForBlock(String blockId) {
        if (!blockId) {
            throw new IllegalArgumentException('blockId is required')
        }
        String hash = sha256Hex(blockId).substring(0, 24)
        return "${UID_PREFIX}${hash}@${UID_DOMAIN}"
    }

    static boolean isPlannerUid(String uid) {
        uid != null && uid.startsWith(UID_PREFIX) && uid.endsWith("@${UID_DOMAIN}")
    }

    static boolean hasOwnershipMarker(String description) {
        description != null && description.contains(OWNERSHIP_MARKER)
    }

    /**
     * True only when event is on the managed calendar and has both a planner UID and ownership marker.
     * Deterministic-UID collisions without a marker (or on the wrong calendar) are not owned and must
     * not be adopted or overwritten.
     * Blank/null {@code managedCalendarName} never skips calendar verification — ownership is false.
     */
    static boolean isOwned(CalendarEvent event, String managedCalendarName) {
        if (event == null) {
            return false
        }
        if (managedCalendarName == null || managedCalendarName.trim().isEmpty()) {
            return false
        }
        if (event.calendarName != managedCalendarName) {
            return false
        }
        return isPlannerUid(event.uid) && hasOwnershipMarker(event.description)
    }

    static String buildDescription(String blockId, String planId, String titleExtra = null) {
        def sb = new StringBuilder()
        sb.append(OWNERSHIP_MARKER).append('\n')
        sb.append(BLOCK_ID_PREFIX).append(blockId ?: '').append('\n')
        sb.append(PLAN_ID_PREFIX).append(planId ?: '').append('\n')
        if (titleExtra) {
            sb.append(titleExtra).append('\n')
        }
        return sb.toString()
    }

    static String extractBlockId(String description) {
        if (description == null) {
            return null
        }
        def m = description =~ /(?m)^${Pattern.quote(BLOCK_ID_PREFIX)}(.+)$/
        if (m.find()) {
            return m.group(1)?.trim()
        }
        // also accept inline form without line anchors
        def idx = description.indexOf(BLOCK_ID_PREFIX)
        if (idx < 0) {
            return null
        }
        String rest = description.substring(idx + BLOCK_ID_PREFIX.length())
        int end = rest.indexOf('\n')
        return (end >= 0 ? rest.substring(0, end) : rest).trim() ?: null
    }

    static boolean descriptionHasBlockId(String description, String blockId) {
        if (!blockId) {
            return false
        }
        return blockId == extractBlockId(description)
    }

    private static String sha256Hex(String s) {
        MessageDigest md = MessageDigest.getInstance('SHA-256')
        byte[] dig = md.digest(s.getBytes(StandardCharsets.UTF_8))
        return dig.collect { String.format('%02x', it & 0xff) }.join()
    }
}
