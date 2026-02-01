# Proactive Connection Pool Reset Strategy

## Overview

Added proactive connection pool monitoring and reset to prevent connection exhaustion. The system now monitors pool usage and automatically resets the pool when it reaches 50% capacity.

## How It Works

### 1. Monitoring (`checkAndResetPoolIfNeeded`)
- Called before each DELETE operation
- Checks current pool usage: `(leased / max) * 100`
- Threshold: 50% of max connections
- When threshold is exceeded → triggers reset

### 2. Reset (`resetPoolForCalendar`)
- Safely shuts down old HttpClient
- Safely shuts down old ConnectionManager
- Creates fresh client and manager
- Prevents reset spam (min 60 seconds between resets per calendar)
- Graceful error handling - continues even if reset fails

### 3. Client Creation (`createHttpClientForCalendar`)
- Extracted from original `configureCalDavHttpClients()`
- Re-used during pool reset
- Recreates auth, connection manager, and http client
- Configures same settings as initial setup

## Configuration

Default threshold is 50%. To customize, add to config:
```yaml
caldav:
  # ... other config
  poolThresholdPercent: 60  # Reset at 60% usage instead
```

Or modify in code:
```groovy
def poolThresholdPercent = 50  // Reset pool when usage exceeds 50%
```

## Benefits

✅ **Prevents exhaustion cascade**: Resets pool before hitting max, avoiding deadlock
✅ **Automatic recovery**: No manual intervention needed
✅ **Per-calendar resets**: Only affected calendars are reset
✅ **Controlled frequency**: One reset per calendar per 60 seconds max
✅ **Graceful degradation**: Sync continues even if reset fails
✅ **Better logging**: Clear visibility into pool health and resets

## Example Flow

1. Delete operation starts
2. Pool check: 300/500 leased = 60% usage
3. Exceeds 50% threshold → reset triggered
4. Old client/manager shut down
5. New fresh client/manager created (0 leased)
6. Delete operation retried with new pool
7. Success

## Logging

Look for these messages in logs:

```
WARN  - Connection pool for J&J at 60% usage (300/500). Resetting pool proactively...
INFO  - Resetting HTTP connection pool for calendar: J&J
INFO  - Closed old HttpClient for J&J
INFO  - Shutdown old ConnectionManager for J&J
INFO  - Successfully reset HTTP connection pool for calendar: J&J
```

## Interaction with Retry Logic

- Reset happens **before** each retry attempt
- If a calendar is in a failure loop, its pool will be reset once per minute
- After reset, next retry gets a fresh pool
- Exponential backoff + pool reset = robust recovery

## Safety Considerations

1. **Thread safety**: 
   - Each calendar has separate client/manager
   - Maps are updated atomically
   - Multiple resets prevented by timestamp tracking

2. **Auth preservation**:
   - Auth scheme re-read from config each reset
   - OAuth tokens refreshed automatically
   - BASIC auth credentials from config

3. **No request loss**:
   - Old connections are shut down gracefully
   - In-flight requests use old client (unaffected)
   - New requests use new client
   - Graceful transition between pools

## Edge Cases Handled

- Pool never accessed (cm == null) → skipped
- Reset called multiple times → throttled to 60s
- Config missing for calendar → logged, not fatal
- Old client/manager shutdown fails → logged, continues
- Create new client fails → logged, pool degraded but sync continues

## Performance Impact

- Minimal: Pool check is just 2 integer comparisons
- Only reset when needed (>50%)
- Reset happens asynchronously before operation
- No performance penalty when pool is healthy
