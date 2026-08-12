package todoistcaldavsync.planner.scheduling

import todoistcaldavsync.planner.config.PlannerConfig
import todoistcaldavsync.planner.config.PlannerConfig.WeatherConfig
import todoistcaldavsync.planner.config.PlannerConfig.WeatherTaskRule
import todoistcaldavsync.planner.domain.Task
import todoistcaldavsync.planner.domain.WeatherEvaluation
import todoistcaldavsync.planner.domain.WeatherForecast
import todoistcaldavsync.planner.domain.WeatherInterval

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/**
 * Pure weather feasibility/score evaluation. No I/O.
 *
 * Multi-hour tasks evaluate every overlapping forecast bucket; any violating
 * bucket fails hard requirements. Missing coverage cannot silently pass when
 * policy is fail-closed. Daylight is evaluated in the forecast/config timezone.
 */
class WeatherEvaluator {
    static final long DEFAULT_SUITABILITY_BONUS = 35L

    private final WeatherConfig weather
    private final ZoneId fallbackZone

    WeatherEvaluator(PlannerConfig config) {
        if (config == null) {
            throw new IllegalArgumentException('PlannerConfig is required')
        }
        this.weather = config.weather ?: WeatherConfig.disabled()
        this.fallbackZone = config.timezone
    }

    WeatherEvaluator(WeatherConfig weather, ZoneId fallbackZone) {
        this.weather = weather ?: WeatherConfig.disabled()
        this.fallbackZone = fallbackZone ?: ZoneId.of('UTC')
    }

    boolean isEnabled() {
        weather?.enabled == true
    }

    /**
     * Evaluate task placement [start, end) against forecast and matching rules.
     * @param now planning clock for freshness/stale checks
     */
    WeatherEvaluation evaluate(Task task, Instant start, Instant end,
                               WeatherForecast forecast, Instant now = null) {
        if (!isEnabled()) {
            return WeatherEvaluation.notApplicable('Weather evaluation disabled')
        }
        if (task == null) {
            return WeatherEvaluation.notApplicable('No task')
        }
        WeatherTaskRule rule = matchRule(task)
        if (rule == null) {
            return WeatherEvaluation.notApplicable('Task does not match any weather rule')
        }
        if (start == null || end == null || !end.isAfter(start)) {
            return infeasible(rule, forecast, 'Invalid placement interval', 'interval', null, null, [])
        }
        if (forecast == null) {
            return missingForecast(rule, now)
        }

        Instant clock = now ?: forecast.retrievedAt ?: forecast.issuedAt
        if (weather.maxAge != null && forecast.isStale(clock, weather.maxAge)) {
            return stale(rule, forecast, clock)
        }

        List<WeatherInterval> covering = forecast.intervalsOverlapping(start, end)
        if (!covering) {
            return missingCoverage(rule, forecast, start, end)
        }
        // Fail-closed: require continuous coverage of [start,end) by forecast buckets
        if (!hasContinuousCoverage(covering, start, end)) {
            return missingCoverage(rule, forecast, start, end)
        }

        List<Instant> hours = covering.collect { it.start }
        // Hard requirements across every overlapping bucket
        for (WeatherInterval iv : covering) {
            def fail = checkHardRequirements(rule, iv)
            if (fail != null) {
                if (fail.missingObservation) {
                    return missingObservation(rule, forecast, fail, hours)
                }
                return infeasible(rule, forecast, fail.reason, fail.field, fail.observed, fail.threshold, hours)
            }
        }

        // Hard daylight only when require.daylight=true (preferred.daylight is soft-only).
        // Explicit false and absent are both non-required.
        boolean hardDaylight = rule.requireDaylight == Boolean.TRUE
        if (hardDaylight) {
            def daylightFail = checkDaylight(rule, forecast, start, end)
            if (daylightFail != null) {
                if (daylightFail.missingObservation) {
                    return missingObservation(rule, forecast, daylightFail, hours)
                }
                return infeasible(rule, forecast, daylightFail.reason, 'daylight',
                    daylightFail.observed, daylightFail.threshold, hours)
            }
        }

        // Soft preferred confidence / daylight — never hard-infeasible
        long bonus = 0L
        Map details = [matchedLabels: rule.matchLabels] as Map
        String reason = "Weather rule '${rule.name}' satisfied for placement"
        boolean preferredDaylightWanted = rule.preferredDaylight == Boolean.TRUE
        boolean preferredDaylightMet = true

        Double minConf = rule.preferredForecastConfidenceMin
        if (minConf != null) {
            Double worstConf = covering.collect { it.confidence }.findAll { it != null }.min()
            if (worstConf == null) {
                // missing confidence: soft — no bonus, not hard fail
                details.preferredConfidenceMet = false
            } else if (worstConf + 1e-9 >= minConf) {
                bonus += weather.suitabilityBonus
                details.preferredConfidenceMet = true
            } else {
                details.preferredConfidenceMet = false
            }
        } else if (hardDaylight || preferredDaylightWanted) {
            // Daylight-oriented rules: base bonus applied when daylight holds (below)
            bonus += 0L
        } else {
            // Feasible match still gets mild suitability bonus when preferred empty
            bonus += Math.max(0L, weather.suitabilityBonus / 2L)
        }

        // Preferred daylight soft (require.daylight absent/false does not harden this)
        if (preferredDaylightWanted) {
            def daylightFail = checkDaylight(rule, forecast, start, end)
            if (daylightFail != null) {
                preferredDaylightMet = false
                bonus = 0L
                reason = "Weather rule '${rule.name}' feasible but preferred daylight not met" +
                    (daylightFail.reason ? " (${daylightFail.reason})" : '')
                details.preferredDaylightMet = false
                details.preferredDaylightObserved = daylightFail.observed
            } else {
                preferredDaylightMet = true
                bonus = Math.max(bonus, weather.suitabilityBonus)
                details.preferredDaylightMet = true
            }
        } else if (hardDaylight) {
            // Required daylight already verified — award suitability bonus
            bonus = Math.max(bonus, weather.suitabilityBonus)
            details.requireDaylightMet = true
        }

        if (minConf != null && details.preferredConfidenceMet == true && preferredDaylightMet) {
            bonus = Math.max(bonus, weather.suitabilityBonus)
        }

        return WeatherEvaluation.builder()
            .result(WeatherEvaluation.RESULT_FEASIBLE)
            .hardInfeasible(false)
            .scoreDelta(bonus)
            .ruleId(rule.id)
            .ruleName(rule.name)
            .reason(reason)
            .provider(forecast.provider)
            .forecastIssuedAt(forecast.issuedAt)
            .forecastRetrievedAt(forecast.retrievedAt)
            .latitude(forecast.latitude)
            .longitude(forecast.longitude)
            .relevantHours(hours)
            .alternativesSignal(preferredDaylightWanted && !preferredDaylightMet)
            .details(details)
            .build()
    }

    WeatherTaskRule matchRule(Task task) {
        if (task == null || !weather?.taskRules) {
            return null
        }
        Set<String> labels = (task.labels ?: []).collect { it.toLowerCase(Locale.ROOT) } as Set
        // First matching rule in config order wins (deterministic)
        return weather.taskRules.find { rule ->
            rule.matchLabels.any { labels.contains(it.toLowerCase(Locale.ROOT)) }
        }
    }

    private WeatherEvaluation missingForecast(WeatherTaskRule rule, Instant now) {
        String fallback = (weather.fallback ?: 'fail_closed').toLowerCase(Locale.ROOT)
        if (fallback in ['fail_open', 'open', 'allow']) {
            return WeatherEvaluation.builder()
                .result(WeatherEvaluation.RESULT_UNKNOWN)
                .hardInfeasible(false)
                .scoreDelta(0L)
                .ruleId(rule.id)
                .ruleName(rule.name)
                .reason("No forecast available; fail-open policy allows scheduling under rule '${rule.name}'")
                .alternativesSignal(false)
                .details([fallback: fallback])
                .build()
        }
        return WeatherEvaluation.builder()
            .result(WeatherEvaluation.RESULT_UNKNOWN)
            .hardInfeasible(true)
            .scoreDelta(0L)
            .ruleId(rule.id)
            .ruleName(rule.name)
            .reason("No forecast available; fail-closed policy blocks weather-sensitive task under rule '${rule.name}'")
            .alternativesSignal(true)
            .details([fallback: fallback])
            .build()
    }

    private WeatherEvaluation stale(WeatherTaskRule rule, WeatherForecast forecast, Instant now) {
        String fallback = (weather.fallback ?: 'fail_closed').toLowerCase(Locale.ROOT)
        String reason = "Forecast issued at ${forecast.issuedAt} exceeds max age ${weather.maxAge} (now=${now})"
        if (fallback in ['fail_open', 'open', 'allow']) {
            return WeatherEvaluation.builder()
                .result(WeatherEvaluation.RESULT_STALE)
                .hardInfeasible(false)
                .scoreDelta(0L)
                .ruleId(rule.id)
                .ruleName(rule.name)
                .reason(reason + '; fail-open allows scheduling')
                .provider(forecast.provider)
                .forecastIssuedAt(forecast.issuedAt)
                .forecastRetrievedAt(forecast.retrievedAt)
                .latitude(forecast.latitude)
                .longitude(forecast.longitude)
                .alternativesSignal(false)
                .details([fallback: fallback, maxAge: weather.maxAge?.toString()])
                .build()
        }
        return WeatherEvaluation.builder()
            .result(WeatherEvaluation.RESULT_STALE)
            .hardInfeasible(true)
            .scoreDelta(0L)
            .ruleId(rule.id)
            .ruleName(rule.name)
            .reason(reason + '; fail-closed blocks weather-sensitive task')
            .provider(forecast.provider)
            .forecastIssuedAt(forecast.issuedAt)
            .forecastRetrievedAt(forecast.retrievedAt)
            .latitude(forecast.latitude)
            .longitude(forecast.longitude)
            .alternativesSignal(true)
            .details([fallback: fallback, maxAge: weather.maxAge?.toString()])
            .build()
    }

    private WeatherEvaluation missingCoverage(WeatherTaskRule rule, WeatherForecast forecast,
                                              Instant start, Instant end) {
        String fallback = (weather.fallback ?: 'fail_closed').toLowerCase(Locale.ROOT)
        String reason = "Forecast missing coverage for [${start}, ${end}) under rule '${rule.name}'"
        boolean open = fallback in ['fail_open', 'open', 'allow']
        return WeatherEvaluation.builder()
            .result(WeatherEvaluation.RESULT_UNKNOWN)
            .hardInfeasible(!open)
            .scoreDelta(0L)
            .ruleId(rule.id)
            .ruleName(rule.name)
            .reason(open ? reason + '; fail-open allows scheduling' : reason + '; fail-closed blocks placement')
            .provider(forecast?.provider)
            .forecastIssuedAt(forecast?.issuedAt)
            .forecastRetrievedAt(forecast?.retrievedAt)
            .latitude(forecast?.latitude)
            .longitude(forecast?.longitude)
            .alternativesSignal(!open)
            .details([fallback: fallback, start: start?.toString(), end: end?.toString()])
            .build()
    }

    private WeatherEvaluation infeasible(WeatherTaskRule rule, WeatherForecast forecast,
                                         String reason, String field, Object observed, Object threshold,
                                         List<Instant> hours) {
        WeatherEvaluation.builder()
            .result(WeatherEvaluation.RESULT_INFEASIBLE)
            .hardInfeasible(true)
            .scoreDelta(0L)
            .ruleId(rule.id)
            .ruleName(rule.name)
            .reason(reason)
            .provider(forecast?.provider)
            .forecastIssuedAt(forecast?.issuedAt)
            .forecastRetrievedAt(forecast?.retrievedAt)
            .latitude(forecast?.latitude)
            .longitude(forecast?.longitude)
            .observedField(field)
            .observedValue(observed)
            .threshold(threshold)
            .relevantHours(hours)
            .alternativesSignal(true)
            .details([matchedLabels: rule?.matchLabels])
            .build()
    }

    /**
     * Missing required observation within a covered bucket. Fail-closed blocks hard;
     * fail-open allows scheduling but never silently treats the null as a good value.
     */
    private WeatherEvaluation missingObservation(WeatherTaskRule rule, WeatherForecast forecast,
                                                 Map fail, List<Instant> hours) {
        String fallback = (weather.fallback ?: 'fail_closed').toLowerCase(Locale.ROOT)
        boolean open = fallback in ['fail_open', 'open', 'allow']
        String field = fail.field?.toString() ?: 'observation'
        String base = fail.reason?.toString() ?:
            "Missing required observation '${field}' for rule '${rule.name}'"
        String reason = open
            ? base + "; fail-open allows scheduling but does not treat missing ${field} as satisfied"
            : base + '; fail-closed blocks weather-sensitive placement'
        Map details = new LinkedHashMap()
        details.matchedLabels = rule?.matchLabels
        details.fallback = fallback
        details.missingObservation = true
        details.missingField = field
        if (fail.bucketStart != null) {
            details.bucketStart = fail.bucketStart.toString()
        }
        if (fail.bucketEnd != null) {
            details.bucketEnd = fail.bucketEnd.toString()
        }
        if (fail.threshold != null) {
            details.requiredThreshold = fail.threshold
        }
        return WeatherEvaluation.builder()
            .result(WeatherEvaluation.RESULT_UNKNOWN)
            .hardInfeasible(!open)
            .scoreDelta(0L)
            .ruleId(rule.id)
            .ruleName(rule.name)
            .reason(reason)
            .provider(forecast?.provider)
            .forecastIssuedAt(forecast?.issuedAt)
            .forecastRetrievedAt(forecast?.retrievedAt)
            .latitude(forecast?.latitude)
            .longitude(forecast?.longitude)
            .observedField(field)
            .observedValue(null)
            .threshold(fail.threshold)
            .relevantHours(hours)
            .alternativesSignal(!open)
            .details(details)
            .build()
    }

    /**
     * Hard thresholds are inclusive on both sides:
     * - maximums: observed &lt;= max is feasible; observed &gt; max is infeasible
     * - minimums: observed &gt;= min is feasible; observed &lt; min is infeasible
     * Null observations for a required threshold are missing-observation (not silent pass).
     */
    private static Map checkHardRequirements(WeatherTaskRule rule, WeatherInterval iv) {
        if (rule.precipitationProbabilityMax != null) {
            if (iv.precipitationProbability == null) {
                return missingObs(rule, 'precipitation_probability',
                    "Missing precipitation probability observation for rule '${rule.name}' (required max ${formatNum(rule.precipitationProbabilityMax)}%)",
                    rule.precipitationProbabilityMax, iv)
            }
            if (!Double.isFinite(iv.precipitationProbability)) {
                return invalidObs(rule, 'precipitation_probability', iv.precipitationProbability,
                    rule.precipitationProbabilityMax, iv)
            }
            if (iv.precipitationProbability > rule.precipitationProbabilityMax + 1e-9) {
                return [reason: "Precipitation probability (${formatNum(iv.precipitationProbability)}%) exceeded the task rule maximum (${formatNum(rule.precipitationProbabilityMax)}%).",
                        field: 'precipitation_probability',
                        observed: iv.precipitationProbability,
                        threshold: rule.precipitationProbabilityMax]
            }
        }
        if (rule.precipitationMmMax != null) {
            if (iv.precipitationMm == null) {
                return missingObs(rule, 'precipitation_mm',
                    "Missing precipitation amount observation for rule '${rule.name}' (required max ${formatNum(rule.precipitationMmMax)} mm)",
                    rule.precipitationMmMax, iv)
            }
            if (!Double.isFinite(iv.precipitationMm)) {
                return invalidObs(rule, 'precipitation_mm', iv.precipitationMm,
                    rule.precipitationMmMax, iv)
            }
            if (iv.precipitationMm > rule.precipitationMmMax + 1e-9) {
                return [reason: "Precipitation amount (${formatNum(iv.precipitationMm)} mm) exceeded the task rule maximum (${formatNum(rule.precipitationMmMax)} mm).",
                        field: 'precipitation_mm',
                        observed: iv.precipitationMm,
                        threshold: rule.precipitationMmMax]
            }
        }
        if (rule.windSpeedKphMax != null) {
            if (iv.windSpeedKph == null) {
                return missingObs(rule, 'wind_speed_kph',
                    "Missing wind speed observation for rule '${rule.name}' (required max ${formatNum(rule.windSpeedKphMax)} kph)",
                    rule.windSpeedKphMax, iv)
            }
            if (!Double.isFinite(iv.windSpeedKph)) {
                return invalidObs(rule, 'wind_speed_kph', iv.windSpeedKph,
                    rule.windSpeedKphMax, iv)
            }
            if (iv.windSpeedKph > rule.windSpeedKphMax + 1e-9) {
                return [reason: "Wind speed (${formatNum(iv.windSpeedKph)} kph) exceeded the task rule maximum (${formatNum(rule.windSpeedKphMax)} kph).",
                        field: 'wind_speed_kph',
                        observed: iv.windSpeedKph,
                        threshold: rule.windSpeedKphMax]
            }
        }
        if (rule.temperatureMinC != null) {
            if (iv.temperatureC == null) {
                return missingObs(rule, 'temperature_c',
                    "Missing temperature observation for rule '${rule.name}' (required min ${formatNum(rule.temperatureMinC)}°C)",
                    rule.temperatureMinC, iv)
            }
            if (!Double.isFinite(iv.temperatureC)) {
                return invalidObs(rule, 'temperature_c', iv.temperatureC,
                    rule.temperatureMinC, iv)
            }
            if (iv.temperatureC < rule.temperatureMinC - 1e-9) {
                return [reason: "Temperature (${formatNum(iv.temperatureC)}°C) below the task rule minimum (${formatNum(rule.temperatureMinC)}°C).",
                        field: 'temperature_min_c',
                        observed: iv.temperatureC,
                        threshold: rule.temperatureMinC]
            }
        }
        if (rule.temperatureMaxC != null) {
            if (iv.temperatureC == null) {
                return missingObs(rule, 'temperature_c',
                    "Missing temperature observation for rule '${rule.name}' (required max ${formatNum(rule.temperatureMaxC)}°C)",
                    rule.temperatureMaxC, iv)
            }
            if (!Double.isFinite(iv.temperatureC)) {
                return invalidObs(rule, 'temperature_c', iv.temperatureC,
                    rule.temperatureMaxC, iv)
            }
            if (iv.temperatureC > rule.temperatureMaxC + 1e-9) {
                return [reason: "Temperature (${formatNum(iv.temperatureC)}°C) above the task rule maximum (${formatNum(rule.temperatureMaxC)}°C).",
                        field: 'temperature_max_c',
                        observed: iv.temperatureC,
                        threshold: rule.temperatureMaxC]
            }
        }
        return null
    }

    private static Map invalidObs(WeatherTaskRule rule, String field, Object observed,
                                  Object threshold, WeatherInterval iv) {
        [
            missingObservation: true,
            reason            : "Invalid non-finite observation '${field}' for rule '${rule.name}': ${observed}",
            field             : field,
            observed          : observed,
            threshold         : threshold,
            bucketStart       : iv?.start,
            bucketEnd         : iv?.end
        ]
    }

    private static Map missingObs(WeatherTaskRule rule, String field, String reason,
                                  Object threshold, WeatherInterval iv) {
        [
            missingObservation: true,
            reason            : reason,
            field             : field,
            observed          : null,
            threshold         : threshold,
            bucketStart       : iv?.start,
            bucketEnd         : iv?.end
        ]
    }

    private Map checkDaylight(WeatherTaskRule rule, WeatherForecast forecast, Instant start, Instant end) {
        ZoneId zone = forecast?.timezone ?: fallbackZone
        // Placement may span local midnights — every local date segment must be in daylight
        LocalDate d0 = start.atZone(zone).toLocalDate()
        LocalDate d1 = end.atZone(zone).minusNanos(1).toLocalDate()
        LocalDate cursor = d0
        while (!cursor.isAfter(d1)) {
            def window = forecast.daylightFor(cursor)
            Instant segStart = cursor == d0 ? start : cursor.atStartOfDay(zone).toInstant()
            Instant segEnd = cursor == d1 ? end : cursor.plusDays(1).atStartOfDay(zone).toInstant()
            if (window == null) {
                // Fall back to hourly is_day flags if present
                List<WeatherInterval> segs = forecast.intervalsOverlapping(segStart, segEnd)
                boolean anyUnknown = segs.any { it.daylight == null }
                boolean anyNight = segs.any { it.daylight == Boolean.FALSE }
                if (!segs) {
                    return [
                        missingObservation: true,
                        reason            : "Missing daylight observation for rule '${rule.name}' on ${cursor}",
                        field             : 'daylight',
                        observed          : null,
                        threshold         : true,
                        bucketStart       : segStart,
                        bucketEnd         : segEnd
                    ]
                }
                if (anyUnknown) {
                    WeatherInterval missing = segs.find { it.daylight == null }
                    return [
                        missingObservation: true,
                        reason            : "Missing daylight observation for rule '${rule.name}' on ${cursor}",
                        field             : 'daylight',
                        observed          : null,
                        threshold         : true,
                        bucketStart       : missing?.start ?: segStart,
                        bucketEnd         : missing?.end ?: segEnd
                    ]
                }
                if (anyNight) {
                    return [reason: "Placement requires daylight under rule '${rule.name}' but daylight data indicates night for ${cursor}.",
                            observed: 'night', threshold: true, field: 'daylight']
                }
            } else if (!window.fullyContains(segStart, segEnd)) {
                return [reason: "Placement requires daylight under rule '${rule.name}' (sunrise ${window.sunrise}, sunset ${window.sunset}).",
                        observed: "${segStart}/${segEnd}", threshold: 'within_sunrise_sunset', field: 'daylight']
            }
            cursor = cursor.plusDays(1)
        }
        return null
    }

    /**
     * True when union of covering intervals fully covers [start, end) without gaps.
     * Boundary-touching intervals are sufficient (half-open).
     */
    static boolean hasContinuousCoverage(List<WeatherInterval> covering, Instant start, Instant end) {
        if (!covering || start == null || end == null || !end.isAfter(start)) {
            return false
        }
        List<WeatherInterval> ordered = covering.toSorted { a, b -> a.start <=> b.start ?: a.end <=> b.end }
        Instant cursor = start
        for (WeatherInterval iv : ordered) {
            if (iv.end <= cursor) {
                continue
            }
            if (iv.start.isAfter(cursor)) {
                return false
            }
            if (iv.end.isAfter(cursor)) {
                cursor = iv.end
            }
            if (!cursor.isBefore(end)) {
                return true
            }
        }
        return !cursor.isBefore(end)
    }

    private static String formatNum(Object n) {
        if (n == null) {
            return 'null'
        }
        if (n instanceof Number) {
            double d = n.doubleValue()
            if (d == Math.rint(d) && Math.abs(d) < 1e12d) {
                return String.valueOf((long) d)
            }
            return String.format(Locale.US, '%.2f', d)
        }
        return n.toString()
    }
}
