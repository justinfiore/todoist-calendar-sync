package todoistcaldavsync.planner.adapters

import com.google.api.client.http.javanet.NetHttpTransport
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import todoistcaldavsync.planner.CalendarProviderConfig
import todoistcaldavsync.planner.domain.CalendarEvent
import todoistcaldavsync.planner.domain.ManagedEventIds
import todoistcaldavsync.planner.domain.ProjectionMetadata
import todoistcaldavsync.planner.state.ProjectionReplacementPermit
import todoistcaldavsync.planner.migration.LegacyMigrationPermit
import todoistcaldavsync.planner.oauth.GoogleOAuthClientMaterialLoader
import todoistcaldavsync.planner.oauth.GoogleOAuthCredentialService
import todoistcaldavsync.planner.oauth.GoogleOAuthScopes
import todoistcaldavsync.planner.oauth.PrivateFileGoogleOAuthTokenStore

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.function.Function
import java.util.function.Supplier

/**
 * Google Calendar v3 adapter behind the planner's provider-neutral calendar ports.
 * Reads are bounded per configured calendar. Mutations are at-most-once and are
 * guarded again here even when composition also uses ManagedCalendarWriteGateway.
 */
final class GoogleCalendarApiGateway implements ProjectionCalendarGateway, CalendarChangeGateway, MigrationCalendarGateway {
    static final String DEFAULT_BASE_URL = 'https://www.googleapis.com/calendar/v3'
    static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10)
    static final int DEFAULT_MAX_PAGES = 20
    static final int DEFAULT_PAGE_SIZE = 250
    static final int DEFAULT_MAX_EVENTS = 5_000
    static final long DEFAULT_MAX_RESPONSE_BYTES = 1_048_576L

    final CalendarProviderConfig.GoogleCalendarApiConfig config
    final String managedCalendarName
    final Set<String> managedCalendarNames
    final ZoneId timezone

    private final URI baseUri
    private final HttpClient client
    private final Supplier<String> accessTokenSupplier
    private final Supplier<Instant> clock
    private final Duration timeout
    private final int maxPagesPerCalendar
    private final int maxResultsPerPage
    private final int maxEventsPerCalendar
    private final long maxResponseBytes
    private final int readAttempts
    private final Function<Integer, String> exceptionClassifier
    private final List<CalendarMapping> calendars

    GoogleCalendarApiGateway(Map options) {
        if (!(options?.config instanceof CalendarProviderConfig.GoogleCalendarApiConfig)) {
            throw new IllegalArgumentException('validated Google calendar provider config is required')
        }
        this.config = options.config as CalendarProviderConfig.GoogleCalendarApiConfig
        this.managedCalendarName = options.managedCalendarName?.toString()
        this.timezone = options.timezone instanceof ZoneId ? options.timezone as ZoneId :
            ZoneId.of((options.timezone ?: 'UTC').toString())
        this.calendars = Collections.unmodifiableList(config.calendars.collect { Map row ->
            String name = row.name?.toString()
            String id = row.id?.toString()
            if (!name || !id) throw new IllegalArgumentException('configured Google calendars require name and id')
            new CalendarMapping(name, id, row.role?.toString())
        })
        if (options.managedCalendarNames != null) {
            if (managedCalendarName || !(options.managedCalendarNames instanceof Collection)) {
                throw new IllegalArgumentException('single and routed managed calendar models cannot be mixed')
            }
            Set<String> requested = options.managedCalendarNames as Set<String>
            Set<String> configured = config.calendars.findAll { it.managed_destination == true }*.name as Set<String>
            if (!requested || requested != configured || !calendars*.name.containsAll(requested) ||
                calendars.any { it.role == 'managed_output' }) {
                throw new IllegalArgumentException('routed managed destinations must match explicitly configured mappings')
            }
            this.managedCalendarNames = Collections.unmodifiableSet(new LinkedHashSet<>(requested))
        } else {
            if (!managedCalendarName || !calendars.any { it.name == managedCalendarName && it.role == 'managed_output' }) {
                throw new IllegalArgumentException('managed calendar must match the configured Google managed_output mapping')
            }
            this.managedCalendarNames = Collections.singleton(managedCalendarName)
        }

        String rawBase = (options.baseUrl ?: options.base_url ?: DEFAULT_BASE_URL).toString()
        this.baseUri = validateBaseUri(URI.create(rawBase), options.allowInsecureHttp == true)
        this.timeout = options.timeout instanceof Duration ? options.timeout as Duration : DEFAULT_TIMEOUT
        this.maxPagesPerCalendar = integerOption(options, 'maxPagesPerCalendar', DEFAULT_MAX_PAGES)
        this.maxResultsPerPage = integerOption(options, 'maxResultsPerPage', DEFAULT_PAGE_SIZE)
        this.maxEventsPerCalendar = integerOption(options, 'maxEventsPerCalendar', DEFAULT_MAX_EVENTS)
        this.maxResponseBytes = options.maxResponseBytes != null ? options.maxResponseBytes as long : DEFAULT_MAX_RESPONSE_BYTES
        this.readAttempts = integerOption(options, 'readAttempts', 3)
        if (timeout.isZero() || timeout.isNegative() || maxPagesPerCalendar < 1 || maxPagesPerCalendar > 1000 ||
            maxResultsPerPage < 1 || maxResultsPerPage > 2500 || maxEventsPerCalendar < 1 ||
            maxResponseBytes < 1 || readAttempts < 1 || readAttempts > 10) {
            throw new IllegalArgumentException('positive bounded Google Calendar timeout/page/result/response limits are required')
        }
        this.client = options.httpClient instanceof HttpClient ? options.httpClient as HttpClient :
            HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build()
        this.clock = options.clock instanceof Supplier ? options.clock as Supplier<Instant> :
            ({ Instant.now() } as Supplier<Instant>)
        this.accessTokenSupplier = tokenSupplier(options)
        def classifier = options.exceptionClassifier
        this.exceptionClassifier = classifier != null ?
            ({ Integer status -> classifier.call(status) } as Function<Integer, String>) :
            ({ Integer status -> classifyStatus(status) } as Function<Integer, String>)
    }

    @Override
    List<CalendarEvent> fetchEvents(Instant rangeStart, Instant rangeEnd) {
        if (rangeStart == null || rangeEnd == null || !rangeEnd.isAfter(rangeStart)) {
            throw new IllegalArgumentException('Google Calendar reads require a positive bounded date range')
        }
        List<CalendarEvent> result = []
        calendars.each { CalendarMapping calendar ->
            Map query = [timeMin: rangeStart.toString(), timeMax: rangeEnd.toString(),
                         singleEvents: 'true', orderBy: 'startTime']
            readAllPages(calendar, query).each { Map resource ->
                CalendarEvent event = fromGoogleEvent(resource, calendar.name, timezone)
                if (event.start.isBefore(rangeEnd) && event.end.isAfter(rangeStart)) result << event
            }
        }
        Collections.unmodifiableList(result)
    }

    @Override
    Map readChanges(String calendarName, Map state, Set<String> candidateUids,
                    Instant rangeStart, Instant rangeEnd) {
        CalendarMapping calendar = calendars.find { it.name == calendarName }
        if (calendar == null) {
            throw new IllegalArgumentException("unknown configured Google calendar '${calendarName}'")
        }
        if (candidateUids == null) requireManagedDestination(calendarName)
        Set<String> candidates = candidateUids == null ? null :
            new HashSet<>(candidateUids.findAll { it }*.toString())
        Closure<Boolean> accepts = { String uid -> candidates == null ? !!uid : candidates.contains(uid) }
        String cursor = state?.cursor?.toString()
        Map<String, String> resourceUids = new LinkedHashMap<>()
        if (state?.resourceUids instanceof Map) {
            (state.resourceUids as Map).each { id, uid ->
                if (id != null && uid != null && accepts(uid.toString())) {
                    resourceUids[id.toString()] = uid.toString()
                }
            }
        }

        List<CalendarEvent> changed = []
        List<Map> deleted = []
        String pageToken = null
        String nextSyncToken = null
        Set<String> seenTokens = new HashSet<>()
        int itemCount = 0
        for (int page = 0; page < maxPagesPerCalendar; page++) {
            Map query = [singleEvents: 'true', maxResults: maxResultsPerPage.toString()]
            if (cursor) query.syncToken = cursor
            if (pageToken) query.pageToken = pageToken
            GatewayResponse response = sendReadWithRetry(eventsPath(calendar.id), query)
            if (response.statusCode() == 410) {
                throw new CalendarCursorExpiredException(
                    "Google Calendar sync cursor expired for ${calendar.name}")
            }
            requireSuccess(response, "list changes for ${calendar.name}")
            Map parsed = parseObject(response.body(), false)
            if (!(parsed.items instanceof List) && parsed.containsKey('items')) {
                throw malformed('Google Calendar change list items must be an array')
            }
            List items = parsed.items instanceof List ? parsed.items as List : []
            itemCount += items.size()
            if (itemCount > maxEventsPerCalendar) {
                throw new GoogleCalendarGatewayException('RESULT_LIMIT',
                    "Google Calendar ${calendar.name} exceeded the configured event result bound")
            }
            items.each { item ->
                if (!(item instanceof Map)) throw malformed('Google Calendar change item must be an object')
                Map resource = item as Map
                String id = resource.id?.toString()
                String uid = managedUid(resource) ?: (id ? resourceUids[id] : null)
                if (!id || !uid || !accepts(uid)) return
                if (candidates == null && !resourceUids.containsKey(id) && !ManagedEventIds.isPlannerUid(uid) &&
                    !ManagedEventIds.hasOwnershipMarker(resource.description?.toString()) &&
                    !resource.description?.toString()?.contains(ProjectionMetadata.PREFIX)) return
                resourceUids[id] = uid
                if (resource.status?.toString() == 'cancelled') {
                    deleted << [id: id, uid: uid]
                } else {
                    changed << fromGoogleEvent(resource, calendar.name, timezone)
                }
            }
            String next = parsed.nextPageToken?.toString()
            if (!next) {
                nextSyncToken = parsed.nextSyncToken?.toString()
                if (!nextSyncToken) {
                    throw malformed('Google Calendar final change page omitted nextSyncToken')
                }
                Map nextState = new LinkedHashMap(state ?: [:])
                nextState.cursor = nextSyncToken
                nextState.resourceUids = Collections.unmodifiableMap(resourceUids)
                return [events: Collections.unmodifiableList(changed),
                        deleted: Collections.unmodifiableList(deleted),
                        state: Collections.unmodifiableMap(nextState)]
            }
            if (!seenTokens.add(next)) {
                throw new GoogleCalendarGatewayException('PAGINATION_LIMIT',
                    "Google Calendar ${calendar.name} repeated a page token")
            }
            pageToken = next
        }
        throw new GoogleCalendarGatewayException('PAGINATION_LIMIT',
            "Google Calendar ${calendar.name} exceeded the configured page bound")
    }

    @Override
    CalendarEvent findEventByUid(String uid) {
        List<CalendarEvent> matches = findIdentityResources(uid)
        if (matches.size() > 1) {
            throw new GoogleCalendarGatewayException('UID_COLLISION',
                "Google Calendar iCalUID collision across configured calendars: ${uid}")
        }
        matches ? matches[0] : null
    }

    private List<CalendarEvent> findIdentityResources(String uid) {
        if (!uid) throw new IllegalArgumentException('event iCalUID is required')
        // A provider event can legitimately match both the iCalUID collision
        // query and the private planner-UID query; count it once.
        Map<String, CalendarEvent> matchesByProviderId = new LinkedHashMap<>()
        calendars.each { CalendarMapping calendar ->
            // Google Event.iCalUID is server-generated/read-only.  Retain the
            // provider-level iCalUID query as a global collision barrier, then
            // resolve the planner's deterministic UID through the writable
            // private extended property carried on planner-owned events.
            readAllPages(calendar, [iCalUID: uid]).each { Map resource ->
                CalendarEvent event = fromGoogleEvent(resource, calendar.name, timezone)
                if (resource.iCalUID?.toString() != uid) {
                    throw new GoogleCalendarGatewayException('MALFORMED_RESPONSE',
                        'Google Calendar iCalUID lookup returned a mismatched event')
                }
                matchesByProviderId["${calendar.id}\u0000${event.id}"] = event
            }
            readAllPages(calendar, [privateExtendedProperty: "plannerUid=${uid}"]).each { Map resource ->
                CalendarEvent event = fromGoogleEvent(resource, calendar.name, timezone)
                String storedPlannerUid = resource.extendedProperties instanceof Map &&
                    (resource.extendedProperties as Map).private instanceof Map
                    ? ((resource.extendedProperties as Map).private as Map).plannerUid?.toString() : null
                if (storedPlannerUid != uid) {
                    throw new GoogleCalendarGatewayException('MALFORMED_RESPONSE',
                        'Google Calendar planner UID lookup returned a mismatched event')
                }
                matchesByProviderId["${calendar.id}\u0000${event.id}"] = CalendarEvent.builder().id(event.id).uid(uid).title(event.title)
                    .description(event.description).calendarName(event.calendarName).start(event.start)
                    .end(event.end).lastModified(event.lastModified).etag(event.etag)
                    .allDay(event.allDay).build()
            }
        }
        new ArrayList<>(matchesByProviderId.values())
    }

    @Override
    void upsertEvent(CalendarEvent event) {
        requireOwnedIncoming(event)
        if (managedCalendarName == null) {
            throw new IllegalStateException('routed writes require an exact-resource projection operation')
        }
        CalendarEvent existing = findEventByUid(event.uid)
        if (existing != null) {
            requireOwnedExisting(existing, ManagedEventIds.extractBlockId(event.description))
        }
        Map body = toGoogleEvent(event, timezone)
        CalendarMapping destination = calendars.find { it.name == event.calendarName }
        String path = existing == null ? eventsPath(destination.id) :
            eventsPath(destination.id) + '/' + segment(existing.id)
        String expectedEtag = event.etag ?: existing?.etag
        Map headers = expectedEtag ? ['If-Match': expectedEtag] : [:]
        GatewayResponse response = send(existing == null ? 'POST' : 'PUT', path, [:],
            JsonOutput.toJson(body).getBytes(StandardCharsets.UTF_8), true, headers)
        requireSuccess(response, existing == null ? 'create event' : 'update event')
        try {
            Map returned = parseObject(response.body(), true)
            String returnedPlannerUid = returned.extendedProperties instanceof Map &&
                (returned.extendedProperties as Map).private instanceof Map
                ? ((returned.extendedProperties as Map).private as Map).plannerUid?.toString() : null
            if (!returned.id || returnedPlannerUid != event.uid) {
                throw new GoogleCalendarGatewayException('MALFORMED_RESPONSE',
                    'Google Calendar mutation returned an invalid event resource')
            }
        } catch (GoogleCalendarGatewayException e) {
            throw ambiguous(e)
        }
    }

    @Override
    void deleteOwnedEvent(String eventUid, String expectedBlockId) {
        if (managedCalendarName == null) throw new IllegalStateException('routed deletion requires an exact-resource projection operation')
        if (!eventUid || !expectedBlockId) {
            throw new IllegalArgumentException('event iCalUID and expected block id are required')
        }
        CalendarEvent live = findEventByUid(eventUid)
        if (live == null) throw new IllegalStateException("Cannot delete missing event uid=${eventUid}")
        requireOwnedExisting(live, expectedBlockId)
        CalendarMapping destination = calendars.find { it.name == live.calendarName }
        GatewayResponse response = send('DELETE', eventsPath(destination.id) + '/' + segment(live.id),
            [:], null, true)
        requireSuccess(response, 'delete event')
    }

    @Override
    List<Map> inventoryLegacyResources() {
        managedCalendarNames.collectMany { String destination ->
            CalendarMapping mapping = requireManagedDestination(destination)
            // Do not expand or time-bound: retain full, restorable resources and visible exclusions.
            readAllPages(mapping, [showDeleted: 'false']).collect { migrationResource(destination, it) }
        }
    }

    @Override
    Map readLegacyResource(String destination, String id) {
        CalendarMapping mapping = requireManagedDestination(destination)
        if (!id) throw new IllegalArgumentException('exact legacy resource ID required')
        GatewayResponse response = sendReadWithRetry(eventsPath(mapping.id) + '/' + segment(id), [:])
        if (response.statusCode() in [404, 410]) return null
        requireSuccess(response, 'read legacy resource')
        Map payload = parseObject(response.body(), false)
        if (payload.id != id) throw malformed('legacy exact read returned another resource')
        payload.status == 'cancelled' ? null : migrationResource(destination, payload)
    }

    @Override
    void deleteLegacyResource(String destination, String id, LegacyMigrationPermit permit) {
        if (permit?.action != 'delete') throw new IllegalArgumentException('legacy deletion capability required')
        Map expected = permit.resource(destination, id)
        permit.requireUnchanged(expected, readLegacyResource(destination, id))
        CalendarMapping mapping = requireManagedDestination(destination)
        requireSuccess(send('DELETE', eventsPath(mapping.id) + '/' + segment(id), [:], null, true,
            ['If-Match': expected.etag.toString()]), 'delete reviewed legacy resource')
    }

    @Override
    Map restoreLegacyResource(String destination, String id, LegacyMigrationPermit permit) {
        if (permit?.action != 'restore') throw new IllegalArgumentException('legacy restoration capability required')
        Map expected = permit.resource(destination, id)
        if (!expected.restorable) throw new IllegalStateException('resource has no verified restoration format')
        CalendarMapping mapping = requireManagedDestination(destination)
        Map body = googleRestorePayload(expected.payload as Map)
        Map properties = new LinkedHashMap(body.extendedProperties ?: [:])
        properties.private = new LinkedHashMap(properties.private ?: [:]) + [todoistMigrationRestore: permit.manifestHash]
        body.extendedProperties = properties
        List<Map> existing = readAllPages(mapping, [iCalUID: expected.uid, showDeleted: 'false'])
        if (existing.size() > 1 || (existing && googleRestorePayload(existing[0]) != body)) {
            throw new IllegalStateException('legacy restore identity changed/collided; preserve human resource')
        }
        CalendarEvent identity = findEventByUid(expected.uid.toString())
        if (identity != null && (!existing || identity.calendarName != destination || identity.id != existing[0].id)) {
            throw new IllegalStateException('legacy restore UID exists in another configured calendar')
        }
        if (!existing) {
            // Import creates a private copy. Never supply both id and iCalUID (Google's contract).
            GatewayResponse response = send('POST', eventsPath(mapping.id) + '/import',
                [conferenceDataVersion: '1', supportsAttachments: 'true', eventLabelVersion: body.eventLabelId ? '1' : '0'],
                JsonOutput.toJson(body).getBytes(StandardCharsets.UTF_8), true)
            requireSuccess(response, 'restore private legacy copy')
            existing = readAllPages(mapping, [iCalUID: expected.uid, showDeleted: 'false'])
        }
        if (existing.size() != 1 || googleRestorePayload(existing[0]) != body || !existing[0].etag) {
            throw new IllegalStateException('legacy restore readback mismatch; reconcile before retry')
        }
        migrationResource(destination, existing[0])
    }

    private Map migrationResource(String destination, Map payload) {
        Map event = null
        try { event = ProjectionMetadata.eventMap(fromGoogleEvent(payload, destination, timezone)) }
        catch (RuntimeException ignored) { /* Export unsupported resources intact, never authorize their deletion. */ }
        [destination: destination, id: payload.id, uid: payload.iCalUID, etag: payload.etag,
            payload: payload, event: event,
            restorable: event != null && (!payload.eventType || payload.eventType == 'default') &&
                !payload.recurrence && !payload.recurringEventId && !!payload.iCalUID]
    }

    private static Map googleRestorePayload(Map payload) {
        Set<String> writable = ['iCalUID', 'start', 'end', 'anyoneCanAddSelf', 'attachments', 'attendees',
            'attendeesOmitted', 'colorId', 'conferenceData', 'description', 'endTimeUnspecified',
            'extendedProperties', 'gadget', 'guestsCanInviteOthers', 'guestsCanModify', 'guestsCanSeeOtherGuests',
            'location', 'organizer', 'reminders', 'sequence', 'source', 'status', 'summary', 'transparency', 'visibility',
            'eventLabelId'] as Set
        payload.findAll { key, value -> writable.contains(key) }
    }

    @Override
    String projectionResourceId(String destination, String uid) {
        requireManagedDestination(destination)
        if (!ManagedEventIds.isPlannerUid(uid)) throw new IllegalArgumentException('planner UID is required')
        java.security.MessageDigest.getInstance('SHA-256')
            .digest((destination + '\u0000' + uid).getBytes(StandardCharsets.UTF_8)).encodeHex().toString()
    }

    @Override
    CalendarEvent readResource(String destination, String resourceId) {
        CalendarMapping mapping = requireManagedDestination(destination)
        if (!resourceId) throw new IllegalArgumentException('exact Google resource ID is required')
        GatewayResponse response = sendReadWithRetry(eventsPath(mapping.id) + '/' + segment(resourceId), [:])
        if (response.statusCode() == 404 || response.statusCode() == 410) return null
        requireSuccess(response, 'read exact projection')
        Map resource = parseObject(response.body(), false)
        if (resource.id != resourceId) throw malformed('exact Google read returned a different resource')
        if (resource.status == 'cancelled') return null
        fromGoogleEvent(resource, destination, timezone)
    }

    @Override
    CalendarEvent createProjection(CalendarEvent target) {
        createProjectionResource(target, null)
    }

    @Override
    CalendarEvent createReplacement(CalendarEvent target, ProjectionReplacementPermit permit) {
        if (permit == null) throw new IllegalArgumentException('replacement permit is required')
        createProjectionResource(target, permit)
    }

    @Override
    CalendarEvent verifyReplacement(CalendarEvent target, ProjectionReplacementPermit permit) {
        requireOwnedIncoming(target)
        if (permit == null) throw new IllegalArgumentException('replacement permit is required')
        permit.validate(target, findIdentityResources(target.uid))
        verifyProjectionWrite(target)
    }

    private CalendarEvent createProjectionResource(CalendarEvent target, ProjectionReplacementPermit permit) {
        requireOwnedIncoming(target)
        ProjectionMetadata.read(target)
        if (target.id != projectionResourceId(target.calendarName, target.uid)) {
            throw new IllegalArgumentException('projection create requires its deterministic provider resource ID')
        }
        List<CalendarEvent> collisions = findIdentityResources(target.uid)
        if (permit != null) permit.validate(target, collisions)
        if ((permit == null && collisions) || readResource(target.calendarName, target.id) != null) {
            throw new IllegalStateException('projection identity already exists; reconcile before create')
        }
        Map body = toGoogleEvent(target, timezone)
        body.id = target.id
        CalendarMapping mapping = requireManagedDestination(target.calendarName)
        GatewayResponse response = send('POST', eventsPath(mapping.id), [:],
            JsonOutput.toJson(body).getBytes(StandardCharsets.UTF_8), true)
        requireSuccess(response, 'create exact projection')
        verifyProjectionWrite(target)
    }

    @Override
    CalendarEvent replaceProjection(CalendarEvent expected, CalendarEvent target) {
        requireOwnedIncoming(target)
        ProjectionMetadata next = ProjectionMetadata.read(target)
        ProjectionMetadata prior = ProjectionMetadata.read(expected)
        if (target.id != expected.id || target.uid != expected.uid || target.calendarName != expected.calendarName ||
            next.value.historical != prior.value.historical ||
            next.value.revision != prior.value.revision + 1 ||
            next.value.members*.occurrenceKey != prior.value.members*.occurrenceKey) {
            throw new IllegalArgumentException('in-place projection updates must preserve identity/membership and advance one revision')
        }
        ProjectionMetadata.requireUnchanged(expected, readResource(expected.calendarName, expected.id))
        CalendarMapping mapping = requireManagedDestination(target.calendarName)
        Map body = toGoogleEvent(target, timezone)
        if (expected.allDay != target.allDay) {
            // PATCH retains omitted nested fields; explicitly remove the old representation.
            [body.start, body.end].each { Map time ->
                if (target.allDay) { time.dateTime = null; time.timeZone = null }
                else time.date = null
            }
        }
        GatewayResponse response = send('PATCH', eventsPath(mapping.id) + '/' + segment(expected.id), [:],
            JsonOutput.toJson(body).getBytes(StandardCharsets.UTF_8), true,
            ['If-Match': expected.etag])
        requireSuccess(response, 'replace exact projection')
        verifyProjectionWrite(target)
    }

    @Override
    CalendarEvent upgradeSingleProjection(CalendarEvent expected, CalendarEvent target) {
        requireOwnedIncoming(target)
        ProjectionMetadata.requireSingleUpgrade(expected, target)
        ProjectionMetadata.requireResourceUnchanged(expected, readResource(expected.calendarName, expected.id))
        ProjectionMetadata.requireResourceUnchanged(expected, findEventByUid(expected.uid))
        CalendarMapping mapping = requireManagedDestination(target.calendarName)
        GatewayResponse response = send('PATCH', eventsPath(mapping.id) + '/' + segment(expected.id), [:],
            JsonOutput.toJson(toGoogleEvent(target, timezone)).getBytes(StandardCharsets.UTF_8), true,
            ['If-Match': expected.etag])
        requireSuccess(response, 'upgrade exact single projection')
        verifyProjectionWrite(target)
    }

    @Override
    void retireProjection(CalendarEvent expected) {
        requireOwnedIncoming(expected)
        ProjectionMetadata.requireUnchanged(expected, readResource(expected.calendarName, expected.id))
        CalendarMapping mapping = requireManagedDestination(expected.calendarName)
        GatewayResponse response = send('DELETE', eventsPath(mapping.id) + '/' + segment(expected.id), [:], null, true,
            ['If-Match': expected.etag])
        requireSuccess(response, 'retire exact projection')
        if (readResource(expected.calendarName, expected.id) != null) {
            throw new GoogleCalendarGatewayException('AMBIGUOUS_WRITE', 'projection retirement was not verified absent')
        }
    }

    private CalendarEvent verifyProjectionWrite(CalendarEvent target) {
        try {
            CalendarEvent live = readResource(target.calendarName, target.id)
            if (live == null || !live.etag || live.uid != target.uid || live.start != target.start || live.end != target.end ||
                live.allDay != target.allDay || live.title != target.title || live.description != target.description) {
                throw new IllegalStateException('projection postconditions differ')
            }
            ProjectionMetadata.read(live)
            live
        } catch (Exception e) { throw ambiguous(e) }
    }

    private CalendarMapping requireManagedDestination(String name) {
        if (!managedCalendarNames.contains(name)) throw new IllegalStateException('destination is not explicitly managed')
        calendars.find { it.name == name }
    }

    private List<Map> readAllPages(CalendarMapping calendar, Map baseQuery) {
        List<Map> rows = []
        String pageToken = null
        Set<String> seenTokens = new HashSet<>()
        for (int page = 0; page < maxPagesPerCalendar; page++) {
            Map query = new LinkedHashMap(baseQuery)
            query.maxResults = maxResultsPerPage.toString()
            if (pageToken) query.pageToken = pageToken
            GatewayResponse response = sendReadWithRetry(eventsPath(calendar.id), query)
            requireSuccess(response, "list events for ${calendar.name}")
            Map parsed = parseObject(response.body(), false)
            if (!(parsed.items instanceof List) && parsed.containsKey('items')) {
                throw malformed('Google Calendar event list items must be an array')
            }
            List items = parsed.items instanceof List ? parsed.items as List : []
            if (rows.size() + items.size() > maxEventsPerCalendar) {
                throw new GoogleCalendarGatewayException('RESULT_LIMIT',
                    "Google Calendar ${calendar.name} exceeded the configured event result bound")
            }
            items.each { item ->
                if (!(item instanceof Map)) throw malformed('Google Calendar event item must be an object')
                rows << new LinkedHashMap(item as Map)
            }
            String next = parsed.nextPageToken?.toString()
            if (!next) return rows
            if (!seenTokens.add(next)) {
                throw new GoogleCalendarGatewayException('PAGINATION_LIMIT',
                    "Google Calendar ${calendar.name} repeated a page token")
            }
            pageToken = next
        }
        throw new GoogleCalendarGatewayException('PAGINATION_LIMIT',
            "Google Calendar ${calendar.name} exceeded the configured page bound")
    }

    private GatewayResponse sendReadWithRetry(String path, Map query) {
        GatewayResponse response
        for (int attempt = 1; attempt <= readAttempts; attempt++) {
            response = send('GET', path, query, null, false)
            if (!isRetryable(response.statusCode()) || attempt == readAttempts) return response
        }
        response
    }

    private GatewayResponse send(String method, String path, Map query, byte[] body, boolean mutation,
                                 Map<String, String> headers = [:]) {
        String token
        try {
            try { token = accessTokenSupplier.get() }
            catch (Exception e) {
                throw new GoogleCalendarGatewayException('AUTHENTICATION',
                    'Google Calendar credential could not be obtained', e)
            }
            if (!token?.trim()) throw new GoogleCalendarGatewayException('AUTHENTICATION',
                'Google Calendar credential is unavailable')
            URI uri = resolve(path, query)
            def builder = HttpRequest.newBuilder(uri).timeout(timeout)
                .header('Authorization', "Bearer ${token.trim()}")
                .header('Accept', 'application/json')
            headers.each { String name, String value -> builder.header(name, value) }
            if (body != null) {
                builder.header('Content-Type', 'application/json; charset=UTF-8')
                    .method(method, HttpRequest.BodyPublishers.ofByteArray(body))
            } else {
                builder.method(method, HttpRequest.BodyPublishers.noBody())
            }
            HttpResponse<InputStream> raw = client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream())
            return new GatewayResponse(raw.statusCode(), readBounded(raw.body()))
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt()
            if (mutation) throw ambiguous(e)
            throw new GoogleCalendarGatewayException('INTERRUPTED', 'Google Calendar request was interrupted', e)
        } catch (GoogleCalendarGatewayException e) {
            if (mutation && e.classification in ['CONTENT_LIMIT', 'MALFORMED_RESPONSE']) throw ambiguous(e)
            throw e
        } catch (Exception e) {
            if (mutation) throw ambiguous(e)
            String classification = e instanceof HttpTimeoutException ? 'TIMEOUT' : 'TRANSPORT'
            throw new GoogleCalendarGatewayException(classification, 'Google Calendar request failed', e)
        } finally {
            token = null
        }
    }

    private String readBounded(InputStream input) {
        if (input == null) return ''
        input.withCloseable { InputStream stream ->
            ByteArrayOutputStream out = new ByteArrayOutputStream((int) Math.min(maxResponseBytes, 8192L))
            byte[] buffer = new byte[8192]
            long total = 0
            int count
            while ((count = stream.read(buffer)) != -1) {
                total += count
                if (total > maxResponseBytes) {
                    throw new GoogleCalendarGatewayException('CONTENT_LIMIT',
                        'Google Calendar response exceeded the configured byte bound')
                }
                out.write(buffer, 0, count)
            }
            out.toString(StandardCharsets.UTF_8)
        }
    }

    private Map parseObject(String body, boolean mutationResponse) {
        try {
            def parsed = new JsonSlurper().parseText(body ?: '')
            if (!(parsed instanceof Map)) throw new IllegalArgumentException('root')
            return parsed as Map
        } catch (GoogleCalendarGatewayException e) {
            throw e
        } catch (Exception e) {
            throw malformed(mutationResponse ? 'Google Calendar mutation returned malformed JSON' :
                'Google Calendar returned malformed JSON', e)
        }
    }

    private void requireSuccess(GatewayResponse response, String operation) {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            String classification = exceptionClassifier.apply(response.statusCode()) ?: 'HTTP_STATUS'
            throw new GoogleCalendarGatewayException(classification,
                "Google Calendar ${operation} failed with HTTP ${response.statusCode()}")
        }
    }

    private void requireOwnedIncoming(CalendarEvent event) {
        if (event == null) throw new IllegalArgumentException('event is required')
        if (!managedCalendarNames.contains(event.calendarName)) {
            throw new IllegalStateException("Refusing Google Calendar write outside managed calendar '${managedCalendarName}'")
        }
        String blockId = ManagedEventIds.extractBlockId(event.description)
        if (!ManagedEventIds.isOwned(event, event.calendarName) || !blockId) {
            throw new IllegalStateException('Refusing Google Calendar write without planner UID, ownership, and block metadata')
        }
    }

    private void requireOwnedExisting(CalendarEvent existing, String expectedBlockId) {
        if (!managedCalendarNames.contains(existing?.calendarName) || !ManagedEventIds.isOwned(existing, existing?.calendarName)) {
            throw new IllegalStateException("Refusing Google Calendar mutation of external or unowned event uid=${existing?.uid}")
        }
        if (!expectedBlockId || !ManagedEventIds.descriptionHasBlockId(existing.description, expectedBlockId)) {
            throw new IllegalStateException("Refusing Google Calendar mutation after block metadata mismatch uid=${existing.uid}")
        }
    }

    private Supplier<String> tokenSupplier(Map options) {
        if (options.accessTokenSupplier instanceof Supplier) return options.accessTokenSupplier as Supplier<String>
        if (options.accessTokenSupplier instanceof Closure) {
            Closure supplied = options.accessTokenSupplier as Closure
            return ({ supplied.call()?.toString() } as Supplier<String>)
        }
        if (options.credentialService instanceof GoogleOAuthCredentialService) {
            GoogleOAuthCredentialService service = options.credentialService as GoogleOAuthCredentialService
            return ({ service.accessToken() } as Supplier<String>)
        }
        GoogleOAuthCredentialService[] holder = new GoogleOAuthCredentialService[1]
        ({ ->
            synchronized (holder) {
                if (holder[0] == null) {
                    holder[0] = new GoogleOAuthCredentialService(
                        new GoogleOAuthClientMaterialLoader().load(config.oauthClientSecretFile),
                        new PrivateFileGoogleOAuthTokenStore(config.tokenStoreDir), GoogleOAuthScopes.EVENTS,
                        config.accountEmail, clock, new NetHttpTransport())
                }
            }
            holder[0].accessToken()
        } as Supplier<String>)
    }

    static CalendarEvent fromGoogleEvent(Map resource, String calendarName, ZoneId defaultZone) {
        try {
            String id = resource.id?.toString()
            String uid = managedUid(resource)
            if (!id || !uid || !(resource.start instanceof Map) || !(resource.end instanceof Map)) {
                throw new IllegalArgumentException('required fields')
            }
            Map startValue = resource.start as Map
            Map endValue = resource.end as Map
            boolean allDay = startValue.date != null
            if (allDay != (endValue.date != null)) throw new IllegalArgumentException('mixed date forms')
            Instant start = allDay ? LocalDate.parse(startValue.date.toString()).atStartOfDay(defaultZone).toInstant() :
                eventInstant(startValue, defaultZone)
            Instant end = allDay ? LocalDate.parse(endValue.date.toString()).atStartOfDay(defaultZone).toInstant() :
                eventInstant(endValue, defaultZone)
            Instant updated = resource.updated == null ? null : Instant.parse(resource.updated.toString())
            CalendarEvent.builder().id(id).uid(uid).title(resource.summary?.toString() ?: '')
                .description(resource.description?.toString()).calendarName(calendarName)
                .start(start).end(end).lastModified(updated).etag(resource.etag?.toString())
                .allDay(allDay).build()
        } catch (GoogleCalendarGatewayException e) {
            throw e
        } catch (Exception e) {
            throw malformed('Google Calendar returned an invalid event resource', e)
        }
    }

    private static String managedUid(Map resource) {
        Map extended = resource.extendedProperties instanceof Map ? resource.extendedProperties as Map : null
        Map privateFields = extended?.private instanceof Map ? extended.private as Map : null
        privateFields?.plannerUid?.toString() ?: resource.iCalUID?.toString()
    }

    static Map toGoogleEvent(CalendarEvent event, ZoneId zone) {
        String blockId = ManagedEventIds.extractBlockId(event.description)
        Map start = event.allDay ? [date: event.start.atZone(zone).toLocalDate().toString()] :
            [dateTime: event.start.toString(), timeZone: zone.id]
        Map end = event.allDay ? [date: event.end.atZone(zone).toLocalDate().toString()] :
            [dateTime: event.end.toString(), timeZone: zone.id]
        [summary: event.title, description: event.description,
         start: start, end: end,
         extendedProperties: [private: [todoistPlannerManaged: '1', plannerUid: event.uid, blockId: blockId]]]
    }

    private static Instant eventInstant(Map value, ZoneId defaultZone) {
        String raw = value.dateTime?.toString()
        if (!raw) throw new IllegalArgumentException('dateTime')
        try { return OffsetDateTime.parse(raw).toInstant() }
        catch (Exception ignored) {
            ZoneId zone = value.timeZone ? ZoneId.of(value.timeZone.toString()) : defaultZone
            return LocalDateTime.parse(raw).atZone(zone).toInstant()
        }
    }

    private URI resolve(String path, Map query) {
        String base = baseUri.toString().replaceAll('/+$', '')
        String encodedQuery = query.collect { key, value -> "${queryValue(key.toString())}=${queryValue(value.toString())}" }.join('&')
        URI.create(base + path + (encodedQuery ? '?' + encodedQuery : ''))
    }

    private static String eventsPath(String calendarId) { "/calendars/${segment(calendarId)}/events" }
    private static String segment(String value) { URLEncoder.encode(value, StandardCharsets.UTF_8).replace('+', '%20') }
    private static String queryValue(String value) { URLEncoder.encode(value, StandardCharsets.UTF_8) }
    private static boolean isRetryable(int status) { status == 429 || status >= 500 }
    private static int integerOption(Map options, String key, int defaultValue) {
        options[key] != null ? options[key] as int : defaultValue
    }

    private static URI validateBaseUri(URI uri, boolean allowHttp) {
        String scheme = uri.scheme?.toLowerCase(Locale.ROOT)
        if (!uri.host || !(scheme == 'https' || (allowHttp && scheme == 'http')) ||
            uri.userInfo || uri.query || uri.fragment) {
            throw new IllegalArgumentException('Google Calendar base_url must be an absolute HTTPS URL')
        }
        uri
    }

    private static String classifyStatus(int status) {
        if (status == 401) return 'AUTHENTICATION'
        if (status == 403) return 'AUTHORIZATION'
        if (status == 404) return 'NOT_FOUND'
        if (status == 409) return 'CONFLICT'
        if (status == 429) return 'RATE_LIMIT'
        if (status >= 500) return 'SERVER_ERROR'
        'HTTP_STATUS'
    }

    private static GoogleCalendarGatewayException malformed(String message, Throwable cause = null) {
        new GoogleCalendarGatewayException('MALFORMED_RESPONSE', message, cause)
    }

    private static GoogleCalendarGatewayException ambiguous(Throwable cause) {
        new GoogleCalendarGatewayException('AMBIGUOUS_WRITE',
            'Google Calendar mutation outcome is unknown and must be reconciled before retry', cause)
    }

    static final class GoogleCalendarGatewayException extends RuntimeException {
        final String classification
        GoogleCalendarGatewayException(String classification, String message, Throwable cause = null) {
            super(message, cause)
            this.classification = classification
        }
    }

    private static final class CalendarMapping {
        final String name
        final String id
        final String role
        CalendarMapping(String name, String id, String role) { this.name = name; this.id = id; this.role = role }
    }

    private static final class GatewayResponse {
        final int status
        final String content
        GatewayResponse(int status, String content) { this.status = status; this.content = content }
        int statusCode() { status }
        String body() { content }
    }
}
