package todoistcaldavsync

/** Pure legacy label/project predicates. Rule order is significant; repeated destinations are OR. */
final class TaskCalendarRouting {
    static boolean includes(Collection labels, String projectName,
                            Collection includedLabels, Collection includedProjects) {
        (labels ?: []).any { (includedLabels ?: []).contains(it) } ||
            (includedProjects ?: []).contains(projectName)
    }

    static String destination(Collection labels, String projectName, Collection<Map> rules) {
        Map match = (rules ?: []).find { Map row ->
            row.rule && row.rule.toString().split(' AND ').every { String term ->
                String name = term.trim()
                boolean negate = name.startsWith('NOT ')
                if (negate) name = name.substring(4)
                boolean matched = name.startsWith('p:')
                    ? projectName == name.substring(2)
                    : (labels ?: []).contains(name)
                negate ? !matched : matched
            }
        }
        match?.calendarName?.toString()
    }

    /** Strict validation for newly opted-in routed configuration, not a new expression language. */
    static void validateExpression(String expression) {
        if (!expression?.trim()) throw new IllegalArgumentException('routing rule must not be blank')
        expression.split(' AND ', -1).each { String term ->
            String name = term.trim()
            if (name.startsWith('NOT ')) name = name.substring(4)
            if (name.startsWith('p:')) name = name.substring(2)
            if (!name.trim() || name != name.trim() ||
                name ==~ /.*\b(AND|OR|NOT)\b.*/ || name.contains('(') || name.contains(')')) {
                throw new IllegalArgumentException("unsupported or malformed routing rule: ${expression}")
            }
        }
    }
}
