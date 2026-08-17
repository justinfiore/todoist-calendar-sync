# Open-Meteo weather integration

Weather is optional and disabled by default. With it disabled, planning behavior is unchanged and no
weather request is made. Enable it only for explicitly labeled weather-sensitive tasks and configure
coordinates, timezone, age/horizon, fallback, and ordered rules. The endpoint is explicit under the
production integration section.

```yaml
planner:
  integration:
    weather:
      base_url: https://api.open-meteo.com/v1/forecast
      timeout: PT10S
      max_response_bytes: 1048576
  weather:
    enabled: true
    provider: open_meteo
    latitude: 40.7128
    longitude: -74.0060
    timezone: America/New_York
    max_age: PT6H
    forecast_horizon_days: 7
    fallback: fail_closed
    task_rules:
      - name: outdoor-dry
        match_labels: [outdoor]
        require:
          precipitation_probability_max: 25
          precipitation_mm_max: 0.5
          wind_speed_kph_max: 25
```

The adapter requests hourly precipitation probability/amount, weather code, temperature, wind, and
daylight plus daily sunrise/sunset. Provider-local civil times are interpreted in the configured
IANA timezone with explicit DST-fold handling; array lengths, ordering, coordinates, response size,
and status are validated. There are no API credentials and no write surface.

`fail_closed` prevents a weather-sensitive placement when forecast data is missing/stale/incomplete.
`fail_open` keeps capacity eligible but records the fallback; use it only as an explicit operational
choice. Weather-hard-invalid prior blocks are proposed for change rather than silently preserved,
and unscheduled/weather displacement remains visible in explanations.

Roll out with recorded fixtures and preview mode first. Compare clear/rain/DST cases, confirm only
matching labels are affected, confirm stale/provider failure behavior, and keep weather disabled if
the configured location/timezone or acceptance criteria cannot be verified.
