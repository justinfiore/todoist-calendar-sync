package todoistcaldavsync.planner.feedback

import todoistcaldavsync.planner.util.BoundedText

import java.util.regex.Matcher

/** Ordered configurable regex action parser. Configuration precompiles patterns. */
final class RegexFeedbackEngine {
    private final List<Map> rules

    RegexFeedbackEngine(Collection<Map> rules) {
        this.rules = Collections.unmodifiableList(new ArrayList<>(rules ?: []))
    }

    FeedbackMatch match(String raw) {
        String text = BoundedText.sanitizeCommand(raw)
        if (!text) return null
        for (Map rule : rules) {
            Matcher m = rule.compiled.matcher(text)
            if (m.matches()) {
                Map<String, String> captures = [:]
                ['feedback', 'reason', 'run', 'task_id'].each { name ->
                    try {
                        String value = m.group(name)
                        if (value != null) captures[name] = BoundedText.sanitizeReason(value)
                    } catch (IllegalArgumentException ignored) {
                        // Named group not present in this configured pattern.
                    }
                }
                return new FeedbackMatch(rule.name.toString(), rule.action.toString(), captures,
                    rule.overrides instanceof Map ? rule.overrides as Map : [:])
            }
        }
        return null
    }

    static final class FeedbackMatch {
        final String ruleName
        final String action
        final Map<String, String> captures
        final Map overrides

        FeedbackMatch(String ruleName, String action, Map captures, Map overrides) {
            this.ruleName = ruleName
            this.action = action
            this.captures = Collections.unmodifiableMap(new LinkedHashMap<>(captures ?: [:]))
            this.overrides = Collections.unmodifiableMap(new LinkedHashMap<>(overrides ?: [:]))
        }
    }
}
