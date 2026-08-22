package todoistcaldavsync.planner.qa

import com.google.api.client.http.javanet.NetHttpTransport
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import todoistcaldavsync.planner.CalendarProviderConfig
import todoistcaldavsync.planner.oauth.GoogleOAuthClientMaterialLoader
import todoistcaldavsync.planner.oauth.GoogleOAuthCredentialService
import todoistcaldavsync.planner.oauth.GoogleOAuthScopes
import todoistcaldavsync.planner.oauth.GoogleOAuthStoreIsolation
import todoistcaldavsync.planner.oauth.PrivateFileGoogleOAuthTokenStore

import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.function.Supplier

/** Explicit QA-only port for Google CalendarList.list and Calendars.insert. */
final class QaCalendarProvisioningService {
    static final URI DEFAULT_BASE_URI = URI.create('https://www.googleapis.com/calendar/v3/')
    static final int MAX_PAGES = 20
    static final int MAX_RESPONSE_BYTES = 1_048_576

    private final CalendarProviderConfig.GoogleCalendarApiConfig config
    private final Path qaRoot
    private final Path stateFile
    private final URI baseUri
    private final HttpClient client
    private final Supplier<String> tokenSupplier
    private final ZoneId timezone

    QaCalendarProvisioningService(Map options) {
        if (!(options?.config instanceof CalendarProviderConfig.GoogleCalendarApiConfig)) {
            throw new IllegalArgumentException('validated Google calendar provider config is required')
        }
        this.config = options.config as CalendarProviderConfig.GoogleCalendarApiConfig
        if (config.qaTokenStoreDir == null) throw new IllegalArgumentException('separate QA token store is required')
        try { GoogleOAuthStoreIsolation.requireDistinct(config.tokenStoreDir, config.qaTokenStoreDir) }
        catch (Exception e) { throw new IllegalArgumentException('normal and QA token stores must be distinct') }

        this.qaRoot = (options.qaRoot as Path)?.toAbsolutePath()?.normalize()
        this.stateFile = (options.stateFile as Path)?.toAbsolutePath()?.normalize()
        if (qaRoot == null || qaRoot.fileName?.toString() != '.qa' || stateFile == null ||
            stateFile == qaRoot || !stateFile.startsWith(qaRoot)) {
            throw new IllegalArgumentException('QA calendar IDs may be persisted only beneath the ignored .qa path')
        }
        URI rawBase = URI.create((options.baseUrl ?: DEFAULT_BASE_URI).toString())
        if (!rawBase.host || (!(rawBase.scheme == 'https') && !(options.allowInsecureHttp == true && rawBase.scheme == 'http'))) {
            throw new IllegalArgumentException('Google Calendar provisioning base URL must use HTTPS')
        }
        this.baseUri = URI.create(rawBase.toString().replaceAll('/+$', '') + '/')
        this.client = options.httpClient instanceof HttpClient ? options.httpClient as HttpClient :
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build()
        this.timezone = options.timezone instanceof ZoneId ? options.timezone as ZoneId : ZoneId.of('UTC')

        if (options.accessTokenSupplier instanceof Supplier) {
            this.tokenSupplier = options.accessTokenSupplier as Supplier<String>
        } else if (options.accessTokenSupplier instanceof Closure) {
            Closure source = options.accessTokenSupplier as Closure
            this.tokenSupplier = ({ source.call()?.toString() } as Supplier<String>)
        } else {
            GoogleOAuthCredentialService[] holder = new GoogleOAuthCredentialService[1]
            this.tokenSupplier = ({ ->
                synchronized (holder) {
                    if (holder[0] == null) {
                        holder[0] = new GoogleOAuthCredentialService(
                            new GoogleOAuthClientMaterialLoader().load(config.oauthClientSecretFile),
                            new PrivateFileGoogleOAuthTokenStore(config.qaTokenStoreDir),
                            GoogleOAuthScopes.QA_CALENDAR_MANAGEMENT, config.accountEmail,
                            ({ Instant.now() } as Supplier<Instant>), new NetHttpTransport())
                    }
                }
                holder[0].accessToken()
            } as Supplier<String>)
        }
    }

    List<Map> list() {
        List<Map> calendars = listAllCalendars()
        requireDedicatedAccount(calendars)
        Collections.unmodifiableList(calendars.collect { Map row ->
            Collections.unmodifiableMap([id: row.id.toString(), name: row.summary?.toString() ?: '',
                primary: row.primary == true])
        })
    }

    List<Map> provision(Collection<QaCalendarSpec> requested) {
        List<QaCalendarSpec> specs = requested?.toList() ?: []
        if (specs.empty || specs*.alias.toSet().size() != specs.size() || specs*.name.toSet().size() != specs.size()) {
            throw new IllegalArgumentException('QA provisioning requires unique named calendar aliases')
        }
        List<Map> existing = listAllCalendars()
        requireDedicatedAccount(existing)
        List<Map> results = specs.collect { QaCalendarSpec spec ->
            List<Map> matches = existing.findAll { it.summary?.toString() == spec.name }
            if (matches.size() > 1) {
                throw new IllegalStateException("Refusing ambiguous reuse of duplicate QA calendar name '${spec.name}'")
            }
            String id = matches ? requiredId(matches[0]) : create(spec.name)
            Collections.unmodifiableMap([alias: spec.alias, name: spec.name, role: spec.role, id: id])
        }
        persistIds(results)
        Collections.unmodifiableList(results)
    }

    private List<Map> listAllCalendars() {
        List<Map> rows = []
        String pageToken = null
        Set<String> tokens = [] as Set
        for (int page = 0; page < MAX_PAGES; page++) {
            Map query = [maxResults: '250']
            if (pageToken) query.pageToken = pageToken
            Map body = request('GET', 'users/me/calendarList', query, null)
            if (body.containsKey('items') && !(body.items instanceof List)) throw malformed()
            (body.items ?: []).each { item ->
                if (!(item instanceof Map) || !item.id) throw malformed()
                rows << new LinkedHashMap(item as Map)
            }
            String next = body.nextPageToken?.toString()
            if (!next) return rows
            if (!tokens.add(next)) throw new IllegalStateException('Google Calendar list repeated a page token')
            pageToken = next
        }
        throw new IllegalStateException('Google Calendar list exceeded the page bound')
    }

    private void requireDedicatedAccount(List<Map> calendars) {
        List<Map> primary = calendars.findAll { it.primary == true }
        if (primary.size() != 1 || !config.accountEmail.equalsIgnoreCase(primary[0].id?.toString() ?: '')) {
            throw new IllegalStateException('dedicated QA account preflight failed; primary calendar does not match configured account')
        }
    }

    private String create(String name) {
        Map returned = request('POST', 'calendars', [:], [summary: name, timeZone: timezone.id])
        String id = requiredId(returned)
        if (returned.summary != null && returned.summary.toString() != name) throw malformed()
        id
    }

    private Map request(String method, String path, Map query, Map body) {
        String token = tokenSupplier.get()?.trim()
        if (!token) throw new IllegalStateException('QA Google OAuth credential is unavailable')
        String suffix = query.collect { key, value ->
            URLEncoder.encode(key.toString(), StandardCharsets.UTF_8) + '=' +
                URLEncoder.encode(value.toString(), StandardCharsets.UTF_8)
        }.join('&')
        URI uri = baseUri.resolve(path + (suffix ? '?' + suffix : ''))
        def builder = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10))
            .header('Authorization', "Bearer ${token}").header('Accept', 'application/json')
        if (body == null) builder.GET()
        else builder.header('Content-Type', 'application/json; charset=UTF-8')
            .method(method, HttpRequest.BodyPublishers.ofString(JsonOutput.toJson(body), StandardCharsets.UTF_8))
        try {
            def response = client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray())
            if (response.body().length > MAX_RESPONSE_BYTES) throw new IllegalStateException('Google Calendar response exceeded the size bound')
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("Google Calendar QA operation failed with HTTP ${response.statusCode()}")
            }
            def parsed = response.body().length ? new JsonSlurper().parse(response.body()) : [:]
            if (!(parsed instanceof Map)) throw malformed()
            parsed as Map
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt()
            throw new IllegalStateException('Google Calendar QA operation was interrupted')
        } catch (IllegalStateException e) {
            throw e
        } catch (Exception e) {
            throw new IllegalStateException('Google Calendar QA operation failed')
        }
    }

    private void persistIds(List<Map> rows) {
        try {
            Files.createDirectories(qaRoot)
            if (Files.isSymbolicLink(qaRoot) || !Files.isDirectory(qaRoot)) throw new IOException('invalid QA root')
            Path parent = stateFile.parent
            Files.createDirectories(parent)
            if (Files.isSymbolicLink(parent) || Files.isSymbolicLink(stateFile)) throw new IOException('invalid QA state path')
            setPermissions(qaRoot, 'rwx------')
            setPermissions(parent, 'rwx------')
            Path temporary = Files.createTempFile(parent, '.calendar-ids-', '.tmp')
            try {
                setPermissions(temporary, 'rw-------')
                Map calendars = new LinkedHashMap()
                rows.each { calendars[it.alias] = [id: it.id] }
                Files.writeString(temporary, JsonOutput.prettyPrint(JsonOutput.toJson([calendars: calendars])) + '\n',
                    StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING)
                Files.move(temporary, stateFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                setPermissions(stateFile, 'rw-------')
            } finally { Files.deleteIfExists(temporary) }
        } catch (Exception e) {
            throw new IllegalStateException('QA calendar IDs could not be persisted beneath .qa')
        }
    }

    private static String requiredId(Map resource) {
        String id = resource.id?.toString()?.trim()
        if (!id) throw malformed()
        id
    }

    private static IllegalStateException malformed() {
        new IllegalStateException('Google Calendar QA operation returned a malformed response')
    }

    private static void setPermissions(Path path, String mode) {
        if (Files.getFileStore(path).supportsFileAttributeView('posix')) {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(mode))
        }
    }
}
