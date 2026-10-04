package todoistcaldavsync.planner.domain

enum EventRole {
    HARD_BLOCKER('hard_blocker'),
    SOFT_BLOCKER('soft_blocker'),
    INFORMATIONAL('informational'),
    MANAGED_OUTPUT('managed_output')

    final String configValue

    EventRole(String configValue) {
        this.configValue = configValue
    }

    static EventRole fromConfig(String value) {
        if (value == null) {
            throw new IllegalArgumentException('Event role must not be null')
        }
        def normalized = value.trim().toLowerCase()
        def match = values().find { it.configValue == normalized }
        if (!match) {
            throw new IllegalArgumentException("Unknown event role: ${value}")
        }
        return match
    }
}
