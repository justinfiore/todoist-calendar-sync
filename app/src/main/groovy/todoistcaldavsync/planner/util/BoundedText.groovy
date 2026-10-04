package todoistcaldavsync.planner.util

/**
 * Bounds and sanitizes user/command text. Deterministic truncation with marker.
 * ISO control characters (except intentional whitespace) are stripped.
 */
final class BoundedText {
    static final int MAX_REASON_CODE_POINTS = 2048
    static final int MAX_COMMAND_CODE_POINTS = 8192
    static final String TRUNCATION_MARKER = '…[truncated]'

    private BoundedText() {}

    /**
     * Sanitize free-text reason / decision fields.
     * Strips ISO controls except tab/LF/CR; normalizes those to space; bounds code points.
     */
    static String sanitizeReason(String raw) {
        sanitize(raw, MAX_REASON_CODE_POINTS)
    }

    /**
     * Bound raw command input before tokenization. Same control rules; larger limit.
     */
    static String sanitizeCommand(String raw) {
        sanitize(raw, MAX_COMMAND_CODE_POINTS)
    }

    static String sanitize(String raw, int maxCodePoints) {
        if (raw == null) {
            return null
        }
        StringBuilder sb = new StringBuilder(Math.min(raw.length(), maxCodePoints + 16))
        int i = 0
        int len = raw.length()
        while (i < len) {
            int cp = raw.codePointAt(i)
            i += Character.charCount(cp)
            if (cp == (int) '\t' || cp == (int) '\n' || cp == (int) '\r' || cp == (int) ' ') {
                // collapse intentional whitespace to single space runs later via normalize
                sb.append(' ')
                continue
            }
            if (cp < 0x20 || (cp >= 0x7F && cp <= 0x9F) || Character.isISOControl(cp)) {
                continue
            }
            sb.appendCodePoint(cp)
        }
        String collapsed = sb.toString().replaceAll(/ +/, ' ').trim()
        return boundCodePoints(collapsed, maxCodePoints)
    }

    static String boundCodePoints(String s, int maxCodePoints) {
        if (s == null) {
            return null
        }
        if (maxCodePoints < 1) {
            return ''
        }
        int count = s.codePointCount(0, s.length())
        if (count <= maxCodePoints) {
            return s
        }
        int markerCp = TRUNCATION_MARKER.codePointCount(0, TRUNCATION_MARKER.length())
        int keep = Math.max(0, maxCodePoints - markerCp)
        int end = s.offsetByCodePoints(0, keep)
        return s.substring(0, end) + TRUNCATION_MARKER
    }

    static int codePointLength(String s) {
        if (s == null) {
            return 0
        }
        return s.codePointCount(0, s.length())
    }
}
