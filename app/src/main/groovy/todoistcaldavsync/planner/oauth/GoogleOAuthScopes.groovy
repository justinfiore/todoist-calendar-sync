package todoistcaldavsync.planner.oauth

import com.google.api.services.calendar.CalendarScopes

final class GoogleOAuthScopes {
    static final String USERINFO_EMAIL = 'https://www.googleapis.com/auth/userinfo.email'
    static final String USERINFO_PROFILE = 'https://www.googleapis.com/auth/userinfo.profile'
    static final Set<String> IDENTITY = Collections.unmodifiableSet(['openid', 'email'] as LinkedHashSet)
    static final Set<String> OPTIONAL_IDENTITY_ADJACENT = Collections.unmodifiableSet(['profile'] as LinkedHashSet)
    static final Set<String> EVENTS = Collections.unmodifiableSet([CalendarScopes.CALENDAR_EVENTS] as LinkedHashSet)
    static final Set<String> QA_CALENDAR_MANAGEMENT = Collections.unmodifiableSet(
        [CalendarScopes.CALENDAR_EVENTS, CalendarScopes.CALENDAR] as LinkedHashSet)

    static Set<String> canonicalize(Collection<String> scopes) {
        scopes.collect { String scope ->
            switch (scope) {
                case USERINFO_EMAIL: return 'email'
                case USERINFO_PROFILE: return 'profile'
                default: return scope
            }
        } as LinkedHashSet<String>
    }

    static boolean matchesRefreshGrant(Collection<String> requiredScopes, Collection<String> returnedScopes) {
        Set<String> required = canonicalize(requiredScopes)
        Set<String> returned = canonicalize(returnedScopes)
        Set<String> allowed = new LinkedHashSet<>(required)
        allowed.addAll(IDENTITY)
        allowed.addAll(OPTIONAL_IDENTITY_ADJACENT)
        returned.containsAll(required) && (returned - allowed).isEmpty()
    }

    private GoogleOAuthScopes() {}
}
