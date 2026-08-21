package todoistcaldavsync.planner

import java.nio.file.Path

/** Immutable, validated selection of exactly one production calendar provider. */
final class CalendarProviderConfig {
    static final String CALDAV = 'caldav'
    static final String GOOGLE_CALENDAR_API = 'google_calendar_api'

    final String provider
    final Map caldav
    final GoogleCalendarApiConfig googleCalendarApi

    CalendarProviderConfig(String provider, Map caldav, GoogleCalendarApiConfig googleCalendarApi) {
        if (provider == CALDAV && caldav != null && googleCalendarApi == null) {
            this.provider = provider
            this.caldav = deepImmutableMap(caldav)
            this.googleCalendarApi = null
            return
        }
        if (provider == GOOGLE_CALENDAR_API && caldav == null && googleCalendarApi != null) {
            this.provider = provider
            this.caldav = null
            this.googleCalendarApi = googleCalendarApi
            return
        }
        throw new IllegalArgumentException('calendar provider model must contain exactly the selected provider configuration')
    }

    private static Map deepImmutableMap(Map source) {
        Map copy = new LinkedHashMap()
        source.each { key, value -> copy[key] = deepImmutable(value) }
        Collections.unmodifiableMap(copy)
    }

    private static Object deepImmutable(Object value) {
        if (value instanceof Map) return deepImmutableMap(value as Map)
        if (value instanceof Collection) {
            return Collections.unmodifiableList((value as Collection).collect { deepImmutable(it) })
        }
        value
    }

    boolean isCalDav() { provider == CALDAV }
    boolean usesGoogleCalendarApi() { provider == GOOGLE_CALENDAR_API }

    static final class GoogleCalendarApiConfig {
        final Path oauthClientSecretFile
        final Path tokenStoreDir
        final String accountEmail
        final int oauthCallbackPort
        final List<Map> calendars

        GoogleCalendarApiConfig(Path oauthClientSecretFile, Path tokenStoreDir, String accountEmail,
                                int oauthCallbackPort, Collection<Map> calendars) {
            this.oauthClientSecretFile = oauthClientSecretFile
            this.tokenStoreDir = tokenStoreDir
            this.accountEmail = accountEmail
            this.oauthCallbackPort = oauthCallbackPort
            this.calendars = Collections.unmodifiableList((calendars ?: []).collect { Map row ->
                Collections.unmodifiableMap(new LinkedHashMap(row))
            })
        }
    }
}
