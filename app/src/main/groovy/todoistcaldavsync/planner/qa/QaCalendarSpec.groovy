package todoistcaldavsync.planner.qa

import groovy.transform.EqualsAndHashCode

@EqualsAndHashCode
final class QaCalendarSpec {
    static final Set<String> ROLES = ['managed_output', 'hard_blocker', 'soft_blocker', 'informational'] as Set

    final String alias
    final String name
    final String role

    QaCalendarSpec(String alias, String name, String role) {
        this.alias = alias?.trim()
        this.name = name?.trim()
        this.role = role?.trim()?.toLowerCase(Locale.ROOT)
        if (!(this.alias ==~ /[a-z][a-z0-9_-]{0,63}/) || !this.name || !(this.role in ROLES)) {
            throw new IllegalArgumentException('QA calendar requires a safe alias, non-empty name, and supported role')
        }
    }

    static QaCalendarSpec parse(String value) {
        List<String> parts = value?.split(/\|/, -1)?.toList() ?: []
        if (parts.size() != 3) {
            throw new IllegalArgumentException('--qa-calendar must be alias|role|name')
        }
        new QaCalendarSpec(parts[0], parts[2], parts[1])
    }
}
