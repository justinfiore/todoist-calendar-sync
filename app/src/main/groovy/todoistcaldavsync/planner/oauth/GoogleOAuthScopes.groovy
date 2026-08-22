package todoistcaldavsync.planner.oauth

import com.google.api.services.calendar.CalendarScopes

final class GoogleOAuthScopes {
    static final Set<String> IDENTITY = Collections.unmodifiableSet(['openid', 'email'] as LinkedHashSet)
    static final Set<String> EVENTS = Collections.unmodifiableSet([CalendarScopes.CALENDAR_EVENTS] as LinkedHashSet)
    static final Set<String> QA_CALENDAR_MANAGEMENT = Collections.unmodifiableSet(
        [CalendarScopes.CALENDAR_EVENTS, CalendarScopes.CALENDAR] as LinkedHashSet)

    private GoogleOAuthScopes() {}
}
