package todoistcaldavsync.planner.adapters

import todoistcaldavsync.planner.domain.WeatherForecast

import java.time.Instant

/**
 * Read-only weather forecast gateway. No mutation authority.
 * Scheduler/evaluator must not couple to provider-specific JSON.
 */
interface WeatherGateway {
    /**
     * Fetch a forecast covering [rangeStart, rangeEnd). Implementations may return a
     * broader horizon. Must not mutate remote state.
     */
    WeatherForecast fetchForecast(Instant rangeStart, Instant rangeEnd)
}

/**
 * Narrow read-only marker (mirrors Todoist/Calendar read gateways).
 */
interface WeatherReadGateway extends WeatherGateway {
    // marker: read-only
}
