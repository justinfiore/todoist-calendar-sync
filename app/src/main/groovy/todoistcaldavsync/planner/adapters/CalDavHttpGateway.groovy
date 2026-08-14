package todoistcaldavsync.planner.adapters

import groovy.xml.XmlSlurper
import todoistcaldavsync.planner.domain.CalendarEvent
import todoistcaldavsync.planner.domain.ManagedEventIds

import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.function.Function

/**
 * Production CalDAV read/write adapter over RFC 4791 HTTP methods.
 * Calendar names are configuration identities and are retained on every event.
 * UID lookup always REPORTs every configured calendar and rejects collisions.
 */
final class CalDavHttpGateway implements CalendarReadGateway, CalendarWriteGateway {
    static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(15)
    private static final DateTimeFormatter ICAL_UTC = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(java.time.ZoneOffset.UTC)
    private static final DateTimeFormatter ICAL_LOCAL = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")
    private static final DateTimeFormatter ICAL_DATE = DateTimeFormatter.BASIC_ISO_DATE

    private final List<CalendarEndpoint> calendars
    private final Map<String, CalendarEndpoint> byName
    private final String managedCalendarName
    private final ZoneId defaultZone
    private final Duration timeout
    private final long maxResponseBytes
    private final HttpClient client
    private final Function<String, String> secretResolver

    CalDavHttpGateway(Map options = [:]) {
        def rawCalendars = options.calendars
        if (!(rawCalendars instanceof Collection) || rawCalendars.isEmpty()) {
            throw new IllegalArgumentException('At least one CalDAV calendar endpoint is required')
        }
        boolean allowHttp = options.allowInsecureHttp == true
        this.calendars = Collections.unmodifiableList((rawCalendars as Collection).collect {
            CalendarEndpoint.fromMap(it as Map, allowHttp)
        })
        if (calendars*.name.toSet().size() != calendars.size()) {
            throw new IllegalArgumentException('CalDAV calendar names must be unique')
        }
        this.byName = Collections.unmodifiableMap(calendars.collectEntries { [(it.name): it] })
        this.managedCalendarName = (options.managedCalendarName ?: options.managed_calendar_name)?.toString()
        if (!managedCalendarName || !byName.containsKey(managedCalendarName)) {
            throw new IllegalArgumentException('managed calendar must name one configured CalDAV calendar')
        }
        this.defaultZone = options.timezone instanceof ZoneId ? options.timezone as ZoneId :
            ZoneId.of((options.timezone ?: 'UTC').toString())
        this.timeout = options.timeout instanceof Duration ? options.timeout as Duration : DEFAULT_TIMEOUT
        this.maxResponseBytes = options.maxResponseBytes != null ? options.maxResponseBytes as long : 2_097_152L
        if (timeout.isZero() || timeout.isNegative() || maxResponseBytes < 1) {
            throw new IllegalArgumentException('CalDAV timeout and max_response_bytes must be positive')
        }
        def resolver = options.secretResolver
        this.secretResolver = resolver != null
            ? ({ String name -> resolver.call(name) } as Function<String, String>)
            : ({ String name -> System.getenv(name) } as Function<String, String>)
        this.client = options.httpClient instanceof HttpClient ? options.httpClient as HttpClient :
            HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build()
    }

    List<String> getCalendarNames() { calendars*.name }
    String getManagedCalendarName() { managedCalendarName }

    @Override
    List<CalendarEvent> fetchEvents(Instant rangeStart, Instant rangeEnd) {
        if (rangeStart == null || rangeEnd == null || !rangeEnd.isAfter(rangeStart)) {
            throw new IllegalArgumentException('rangeStart/rangeEnd must form a positive interval')
        }
        List<CalendarEvent> result = []
        calendars.each { CalendarEndpoint endpoint ->
            report(endpoint, rangeReport(rangeStart, rangeEnd)).each { ReportResource resource ->
                String calendarData = resource.calendarData ?: getResource(endpoint, resource.href)
                result.addAll(parseCalendar(calendarData, endpoint.name, resource.href))
            }
        }
        return Collections.unmodifiableList(result.toSorted { a, b ->
            int c = a.start <=> b.start
            c != 0 ? c : (a.calendarName <=> b.calendarName ?: a.id <=> b.id)
        })
    }

    @Override
    CalendarEvent findEventByUid(String uid) {
        if (!uid) throw new IllegalArgumentException('uid is required')
        List<CalendarEvent> matches = []
        calendars.each { CalendarEndpoint endpoint ->
            report(endpoint, uidReport(uid)).each { ReportResource resource ->
                // GET after UID REPORT is intentional: ownership decisions use live resource data.
                String calendarData = getResource(endpoint, resource.href)
                parseCalendar(calendarData, endpoint.name, resource.href).findAll { it.uid == uid }.each { matches << it }
            }
        }
        if (matches.size() > 1) {
            throw new CalDavGatewayException('UID_COLLISION',
                "CalDAV UID ${uid} exists in multiple configured calendars: ${matches*.calendarName}")
        }
        return matches ? matches[0] : null
    }

    @Override
    void upsertEvent(CalendarEvent event) {
        if (event == null) throw new IllegalArgumentException('event is required')
        if (event.calendarName != managedCalendarName) {
            throw new IllegalStateException("Refusing CalDAV write outside managed calendar ${managedCalendarName}")
        }
        if (!ManagedEventIds.isOwned(event, managedCalendarName) || !event.uid?.startsWith('planner-')) {
            throw new IllegalStateException('Refusing CalDAV PUT without deterministic planner ownership metadata')
        }
        CalendarEndpoint endpoint = byName[managedCalendarName]
        URI target = resourceUri(endpoint, event.uid)
        String body = renderCalendar(event)
        HttpResponse<String> response = send(endpoint, 'PUT', target,
            ['Content-Type': 'text/calendar; charset=utf-8'], body)
        requireStatus(response, [200, 201, 204] as Set, 'PUT', endpoint.name)
    }

    @Override
    void deleteOwnedEvent(String eventUid, String expectedBlockId) {
        if (!eventUid || !expectedBlockId) throw new IllegalArgumentException('eventUid and expectedBlockId are required')
        CalendarEvent live = findEventByUid(eventUid)
        if (!ManagedEventIds.isOwned(live, managedCalendarName) ||
            !ManagedEventIds.descriptionHasBlockId(live.description, expectedBlockId)) {
            throw new IllegalStateException("Refusing delete without live owned block match for uid=${eventUid}")
        }
        CalendarEndpoint endpoint = byName[managedCalendarName]
        URI target = URI.create(live.id)
        if (!isWithinCollection(endpoint.uri, target)) {
            throw new IllegalStateException('Refusing CalDAV delete outside managed collection')
        }
        HttpResponse<String> response = send(endpoint, 'DELETE', target, [:], null)
        requireStatus(response, [200, 202, 204] as Set, 'DELETE', endpoint.name)
    }

    private List<ReportResource> report(CalendarEndpoint endpoint, String body) {
        HttpResponse<String> response = send(endpoint, 'REPORT', endpoint.uri,
            ['Depth': '1', 'Content-Type': 'application/xml; charset=utf-8'], body)
        requireStatus(response, [200, 207] as Set, 'REPORT', endpoint.name)
        try {
            def root = new XmlSlurper(false, false).parseText(response.body() ?: '')
            List<ReportResource> rows = []
            root.depthFirst().findAll { localName(it.name()) == 'response' }.each { node ->
                def hrefNode = node.depthFirst().find { localName(it.name()) == 'href' }
                if (hrefNode == null || !hrefNode.text()) return
                URI href = endpoint.uri.resolve(hrefNode.text().trim())
                if (!isWithinOrigin(endpoint.uri, href)) {
                    throw new CalDavGatewayException('ENDPOINT', 'CalDAV REPORT returned cross-origin href')
                }
                def dataNode = node.depthFirst().find { localName(it.name()) == 'calendar-data' }
                rows << new ReportResource(href, dataNode != null && dataNode.text() ? dataNode.text() : null)
            }
            rows
        } catch (CalDavGatewayException e) {
            throw e
        } catch (Exception e) {
            throw new CalDavGatewayException('SCHEMA', "Invalid CalDAV multistatus for ${endpoint.name}", e)
        }
    }

    private String getResource(CalendarEndpoint endpoint, URI href) {
        HttpResponse<String> response = send(endpoint, 'GET', href, ['Accept': 'text/calendar'], null)
        requireStatus(response, [200] as Set, 'GET', endpoint.name)
        response.body()
    }

    private HttpResponse<String> send(CalendarEndpoint endpoint, String method, URI uri,
                                      Map<String, String> headers, String body) {
        if (!isWithinOrigin(endpoint.uri, uri)) {
            throw new CalDavGatewayException('ENDPOINT', 'Refusing cross-origin CalDAV request')
        }
        String auth = endpoint.authorization(secretResolver)
        try {
            def builder = HttpRequest.newBuilder(uri).timeout(timeout).header('Accept', '*/*')
            if (auth) builder.header('Authorization', auth)
            headers.each { k, v -> builder.header(k, v) }
            builder.method(method, body != null
                ? HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)
                : HttpRequest.BodyPublishers.noBody())
            HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
            if ((response.body() ?: '').getBytes(StandardCharsets.UTF_8).length > maxResponseBytes) {
                throw new CalDavGatewayException('CONTENT', 'CalDAV response exceeded max_response_bytes')
            }
            return response
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt()
            throw new CalDavGatewayException('INTERRUPTED', 'CalDAV request interrupted', e)
        } catch (CalDavGatewayException e) {
            throw e
        } catch (Exception e) {
            throw new CalDavGatewayException('TRANSPORT', "CalDAV ${method} failed for ${endpoint.name}", e)
        } finally {
            auth = null
        }
    }

    static List<CalendarEvent> parseCalendar(String ics, String calendarName, URI href,
                                             ZoneId defaultZone = ZoneId.of('UTC')) {
        if (!ics) throw new CalDavGatewayException('SCHEMA', 'Empty iCalendar resource')
        List<String> lines = unfold(ics)
        List<Map<String, Property>> components = []
        Map<String, Property> current = null
        lines.each { String line ->
            if (line.equalsIgnoreCase('BEGIN:VEVENT')) { current = [:]; return }
            if (line.equalsIgnoreCase('END:VEVENT')) { if (current != null) components << current; current = null; return }
            if (current == null) return
            int colon = line.indexOf(':')
            if (colon <= 0) return
            String left = line.substring(0, colon)
            String value = line.substring(colon + 1)
            List<String> pieces = left.split(';') as List
            String name = pieces[0].toUpperCase(Locale.ROOT)
            Map params = pieces.drop(1).collectEntries { String p ->
                int eq = p.indexOf('='); eq > 0 ? [(p.substring(0, eq).toUpperCase(Locale.ROOT)): p.substring(eq + 1)] : [:]
            }
            current[name] = new Property(value, params)
        }
        List<CalendarEvent> events = []
        components.eachWithIndex { Map<String, Property> props, int idx ->
            String uid = props.UID?.value
            Property startProp = props.DTSTART
            if (!uid || startProp == null) throw new CalDavGatewayException('SCHEMA', 'VEVENT requires UID and DTSTART')
            DateValue start = parseDateValue(startProp, defaultZone)
            DateValue end = props.DTEND != null ? parseDateValue(props.DTEND, defaultZone) : null
            Instant endInstant = end?.instant
            if (endInstant == null && props.DURATION?.value) endInstant = start.instant + Duration.parse(props.DURATION.value)
            if (endInstant == null) endInstant = start.instant + (start.dateOnly ? Duration.ofDays(1) : Duration.ofMinutes(30))
            events << CalendarEvent.builder()
                .id(href.toString() + (components.size() > 1 ? "#${idx}" : ''))
                .uid(unescape(uid))
                .title(unescape(props.SUMMARY?.value ?: ''))
                .description(unescape(props.DESCRIPTION?.value ?: ''))
                .calendarName(calendarName)
                .start(start.instant)
                .end(endInstant)
                .allDay(start.dateOnly)
                .build()
        }
        events
    }

    private static DateValue parseDateValue(Property prop, ZoneId fallback) {
        String raw = prop.value
        boolean dateOnly = prop.params.VALUE?.equalsIgnoreCase('DATE') || raw ==~ /\d{8}/
        if (dateOnly) return new DateValue(LocalDate.parse(raw, ICAL_DATE).atStartOfDay(fallback).toInstant(), true)
        if (raw.endsWith('Z')) return new DateValue(Instant.from(ICAL_UTC.parse(raw)), false)
        ZoneId zone = fallback
        if (prop.params.TZID) zone = ZoneId.of(prop.params.TZID.replace('"', ''))
        new DateValue(LocalDateTime.parse(raw, ICAL_LOCAL).atZone(zone).toInstant(), false)
    }

    private static String renderCalendar(CalendarEvent event) {
        [
            'BEGIN:VCALENDAR', 'VERSION:2.0', 'PRODID:-//todoist-caldav-sync//planner//EN',
            'CALSCALE:GREGORIAN', 'BEGIN:VEVENT',
            "UID:${escape(event.uid)}", "DTSTAMP:${ICAL_UTC.format(Instant.now())}",
            "DTSTART:${ICAL_UTC.format(event.start)}", "DTEND:${ICAL_UTC.format(event.end)}",
            "SUMMARY:${escape(event.title)}", "DESCRIPTION:${escape(event.description ?: '')}",
            'END:VEVENT', 'END:VCALENDAR', ''
        ].join('\r\n')
    }

    private static String rangeReport(Instant start, Instant end) {
        """<?xml version="1.0" encoding="utf-8"?>
<c:calendar-query xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
  <d:prop><d:getetag/><c:calendar-data><c:expand start="${ICAL_UTC.format(start)}" end="${ICAL_UTC.format(end)}"/></c:calendar-data></d:prop>
  <c:filter><c:comp-filter name="VCALENDAR"><c:comp-filter name="VEVENT"><c:time-range start="${ICAL_UTC.format(start)}" end="${ICAL_UTC.format(end)}"/></c:comp-filter></c:comp-filter></c:filter>
</c:calendar-query>"""
    }

    private static String uidReport(String uid) {
        String xmlUid = uid.replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;').replace('"', '&quot;').replace("'", '&apos;')
        """<?xml version="1.0" encoding="utf-8"?>
<c:calendar-query xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
  <d:prop><d:getetag/></d:prop>
  <c:filter><c:comp-filter name="VCALENDAR"><c:comp-filter name="VEVENT"><c:prop-filter name="UID"><c:text-match collation="i;octet">${xmlUid}</c:text-match></c:prop-filter></c:comp-filter></c:comp-filter></c:filter>
</c:calendar-query>"""
    }

    private URI resourceUri(CalendarEndpoint endpoint, String uid) {
        if (!uid) throw new IllegalArgumentException('event UID is required')
        String base = endpoint.uri.toString().replaceAll('/+$', '')
        URI.create(base + '/' + URLEncoder.encode(uid, StandardCharsets.UTF_8).replace('+', '%20') + '.ics')
    }

    private static void requireStatus(HttpResponse<String> response, Set<Integer> expected,
                                      String method, String calendar) {
        if (!expected.contains(response.statusCode())) {
            throw new CalDavGatewayException('HTTP_STATUS',
                "CalDAV ${method} ${calendar} failed with HTTP ${response.statusCode()}")
        }
    }

    private static boolean isWithinOrigin(URI base, URI target) {
        base.scheme?.equalsIgnoreCase(target.scheme) && base.host?.equalsIgnoreCase(target.host) &&
            effectivePort(base) == effectivePort(target)
    }

    private static boolean isWithinCollection(URI base, URI target) {
        isWithinOrigin(base, target) && target.path.startsWith(base.path.replaceAll('/+$', '') + '/')
    }

    private static int effectivePort(URI uri) { uri.port >= 0 ? uri.port : (uri.scheme == 'https' ? 443 : 80) }
    private static String localName(def name) { name?.toString()?.replaceFirst(/^.*:/, '') }
    private static List<String> unfold(String text) {
        List<String> out = []
        text.replace('\r\n', '\n').replace('\r', '\n').split('\n', -1).each { String line ->
            if ((line.startsWith(' ') || line.startsWith('\t')) && out) out[-1] = out[-1] + line.substring(1)
            else out << line
        }
        out
    }
    private static String escape(String s) { (s ?: '').replace('\\', '\\\\').replace('\n', '\\n').replace(',', '\\,').replace(';', '\\;') }
    private static String unescape(String s) { (s ?: '').replace('\\n', '\n').replace('\\N', '\n').replace('\\,', ',').replace('\\;', ';').replace('\\\\', '\\') }

    private static final class Property { String value; Map params; Property(String v, Map p) { value=v; params=p } }
    private static final class DateValue { Instant instant; boolean dateOnly; DateValue(Instant i, boolean d) { instant=i; dateOnly=d } }
    private static final class ReportResource { URI href; String calendarData; ReportResource(URI h, String d) { href=h; calendarData=d } }

    static final class CalendarEndpoint {
        final String name
        final URI uri
        final String authType
        final String username
        final String passwordEnv
        final String tokenEnv
        final String passwordOverride
        final String tokenOverride

        private CalendarEndpoint(String name, URI uri, String authType, String username,
                                 String passwordEnv, String tokenEnv, String passwordOverride, String tokenOverride) {
            this.name=name; this.uri=uri; this.authType=authType; this.username=username
            this.passwordEnv=passwordEnv; this.tokenEnv=tokenEnv
            this.passwordOverride=passwordOverride; this.tokenOverride=tokenOverride
        }

        static CalendarEndpoint fromMap(Map raw, boolean allowHttp) {
            String name = raw.name?.toString()
            URI uri = raw.url != null ? URI.create(raw.url.toString()) : null
            String scheme = uri?.scheme?.toLowerCase(Locale.ROOT)
            if (!name || !uri?.host || !(scheme == 'https' || (allowHttp && scheme == 'http')) || uri.userInfo || uri.query || uri.fragment) {
                throw new IllegalArgumentException('Each CalDAV calendar requires a name and absolute HTTPS url')
            }
            Map auth = raw.auth instanceof Map ? raw.auth as Map : [:]
            String type = (auth.type ?: auth.scheme ?: 'none').toString().toLowerCase(Locale.ROOT)
            if (type == 'basic') type = 'basic'
            if (type in ['bearer', 'oauth2']) type = 'bearer'
            if (!(type in ['none', 'basic', 'bearer'])) throw new IllegalArgumentException("Unsupported CalDAV auth type: ${type}")
            String username = (auth.username ?: auth.basicAuth?.username)?.toString()
            String passwordEnv = (auth.password_env ?: auth.passwordEnv ?: 'CALDAV_AUTH_BASICAUTH_PASSWORD')?.toString()
            String tokenEnv = (auth.token_env ?: auth.tokenEnv)?.toString()
            String passwordOverride = (auth.password_override ?: auth.passwordOverride)?.toString()
            String tokenOverride = (auth.token_override ?: auth.tokenOverride)?.toString()
            if (type == 'basic' && (!username || (!passwordEnv && !passwordOverride))) throw new IllegalArgumentException("Basic CalDAV auth requires username/password_env for ${name}")
            if (type == 'bearer' && (!tokenEnv && !tokenOverride)) throw new IllegalArgumentException("Bearer CalDAV auth requires token_env for ${name}")
            new CalendarEndpoint(name, uri, type, username, passwordEnv, tokenEnv, passwordOverride, tokenOverride)
        }

        String authorization(Function<String, String> resolver) {
            if (authType == 'none') return null
            String secret = authType == 'basic' ? (passwordOverride ?: resolver.apply(passwordEnv)) : (tokenOverride ?: resolver.apply(tokenEnv))
            if (!secret) throw new CalDavGatewayException('AUTHENTICATION', "CalDAV credential unavailable for ${name}")
            if (authType == 'bearer') return "Bearer ${secret}"
            'Basic ' + Base64.encoder.encodeToString("${username}:${secret}".getBytes(StandardCharsets.UTF_8))
        }
    }

    static final class CalDavGatewayException extends RuntimeException {
        final String classification
        CalDavGatewayException(String classification, String message, Throwable cause = null) {
            super(message, cause); this.classification=classification
        }
    }
}
