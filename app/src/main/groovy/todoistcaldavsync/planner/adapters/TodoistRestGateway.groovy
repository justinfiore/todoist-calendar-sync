package todoistcaldavsync.planner.adapters

import groovy.json.JsonOutput
import groovy.json.JsonSlurper

import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.function.Function

/**
 * Production Todoist REST adapter. Reads active tasks/projects and owns the one
 * planner mutation: changing a task's due datetime. Deadline mutation is
 * deliberately refused at this boundary.
 *
 * GETs retry boundedly for transient status codes. Writes are at-most-once: an
 * ambiguous failed write is surfaced for reconciliation rather than replayed.
 */
final class TodoistRestGateway implements TodoistReadGateway, TodoistWriteGateway {
    static final String DEFAULT_BASE_URL = 'https://api.todoist.com/api/v1'
    static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10)
    static final int DEFAULT_PAGE_SIZE = 200
    static final int DEFAULT_MAX_PAGES = 100

    private final URI baseUri
    private final String tokenEnv
    private final Function<String, String> secretResolver
    private final Duration timeout
    private final int maxPages
    private final long maxResponseBytes
    private final HttpClient client
    private final boolean includeProjectNames

    TodoistRestGateway(Map options = [:]) {
        String rawBase = (options.baseUrl ?: options.base_url ?: DEFAULT_BASE_URL).toString()
        this.baseUri = validateBaseUri(URI.create(rawBase), options.allowInsecureHttp == true)
        this.tokenEnv = (options.tokenEnv ?: options.token_env ?: 'TODOIST_ACCESS_TOKEN').toString()
        String tokenOverride = options.tokenOverride?.toString()
        def resolver = options.secretResolver
        this.secretResolver = resolver != null
            ? ({ String name -> resolver.call(name) } as Function<String, String>)
            : ({ String name -> tokenOverride != null ? tokenOverride : System.getenv(name) } as Function<String, String>)
        this.timeout = options.timeout instanceof Duration ? options.timeout as Duration : DEFAULT_TIMEOUT
        this.maxPages = options.maxPages != null ? options.maxPages as int : DEFAULT_MAX_PAGES
        this.maxResponseBytes = options.maxResponseBytes != null ? options.maxResponseBytes as long : 1_048_576L
        this.includeProjectNames = options.includeProjectNames != false
        if (!tokenEnv || timeout.isZero() || timeout.isNegative() || maxPages < 1 || maxPages > 1000 || maxResponseBytes < 1) {
            throw new IllegalArgumentException('Todoist token_env, positive timeout/max_response_bytes, and max_pages 1..1000 are required')
        }
        this.client = options.httpClient instanceof HttpClient
            ? options.httpClient as HttpClient
            : HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build()
    }

    URI getBaseUri() { baseUri }
    String getTokenEnv() { tokenEnv }

    @Override
    List<Map> fetchTasks() {
        List<Map> tasks = fetchCollection('/tasks')
        if (!includeProjectNames || tasks.isEmpty()) {
            return immutableCopies(tasks)
        }
        Map<String, String> projects = fetchCollection('/projects').collectEntries { Map p ->
            p.id != null ? [(p.id.toString()): (p.name ?: '').toString()] : [:]
        }
        return immutableCopies(tasks.collect { Map task ->
            Map copy = new LinkedHashMap(task)
            if (!copy.project_name && copy.project_id != null) {
                copy.project_name = projects[copy.project_id.toString()]
            }
            copy
        })
    }

    @Override
    void updateTaskDue(String taskId, String dueDateTimeIso) {
        if (!taskId || !dueDateTimeIso) {
            throw new IllegalArgumentException('taskId and dueDateTimeIso are required')
        }
        // Intentionally exactly one allowed field. Do not add deadline here.
        byte[] body = JsonOutput.toJson([due_datetime: dueDateTimeIso]).getBytes(StandardCharsets.UTF_8)
        GatewayResponse response = send('POST', "/tasks/${segment(taskId)}", null, body)
        requireSuccess(response, 'update task due')
    }

    @Override
    void updateTaskDeadline(String taskId, String deadlineIso) {
        throw new UnsupportedOperationException('Planner Todoist adapter never mutates deadlines')
    }

    private List<Map> fetchCollection(String path) {
        List<Map> rows = []
        String cursor = null
        Set<String> seen = new HashSet<>()
        for (int page = 0; page < maxPages; page++) {
            String query = "limit=${DEFAULT_PAGE_SIZE}" + (cursor ? "&cursor=${queryValue(cursor)}" : '')
            GatewayResponse response = sendReadWithRetry(path, query)
            requireSuccess(response, "read ${path}")
            def parsed
            try {
                parsed = new JsonSlurper().parseText(response.body() ?: '[]')
            } catch (Exception e) {
                throw new TodoistGatewayException('SCHEMA', "Todoist ${path} returned malformed JSON", e)
            }
            List pageRows
            String next = null
            if (parsed instanceof List) {
                pageRows = parsed as List
            } else if (parsed instanceof Map) {
                def data = parsed.containsKey('results') ? parsed.results :
                    (parsed.containsKey('tasks') ? parsed.tasks :
                        (parsed.containsKey('projects') ? parsed.projects : parsed.items))
                if (!(data instanceof List)) {
                    throw new TodoistGatewayException('SCHEMA', "Todoist ${path} response missing results list")
                }
                pageRows = data as List
                next = (parsed.next_cursor ?: parsed.nextCursor)?.toString()
            } else {
                throw new TodoistGatewayException('SCHEMA', "Todoist ${path} response has invalid root")
            }
            pageRows.each { row ->
                if (!(row instanceof Map)) {
                    throw new TodoistGatewayException('SCHEMA', "Todoist ${path} result must be an object")
                }
                rows << new LinkedHashMap(row as Map)
            }
            if (!next) return rows
            if (!seen.add(next)) {
                throw new TodoistGatewayException('SCHEMA', "Todoist ${path} repeated pagination cursor")
            }
            cursor = next
        }
        throw new TodoistGatewayException('PAGINATION', "Todoist ${path} exceeded max_pages=${maxPages}")
    }

    private GatewayResponse sendReadWithRetry(String path, String query) {
        GatewayResponse last
        for (int attempt = 1; attempt <= 3; attempt++) {
            last = send('GET', path, query, null)
            if (!(last.statusCode() == 429 || last.statusCode() >= 500) || attempt == 3) return last
        }
        return last
    }

    private GatewayResponse send(String method, String path, String query, byte[] body) {
        String token = resolveToken()
        URI uri = resolve(path, query)
        try {
            def builder = HttpRequest.newBuilder(uri).timeout(timeout)
                .header('Authorization', "Bearer ${token}")
                .header('Accept', 'application/json')
            if (body != null) {
                builder.header('Content-Type', 'application/json')
                    .method(method, HttpRequest.BodyPublishers.ofByteArray(body))
            } else {
                builder.method(method, HttpRequest.BodyPublishers.noBody())
            }
            HttpResponse<InputStream> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream())
            return new GatewayResponse(response.statusCode(), readBounded(response.body()))
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt()
            throw new TodoistGatewayException(isMutation(method) ? 'AMBIGUOUS_WRITE' : 'INTERRUPTED',
                "Todoist ${method} request interrupted", e)
        } catch (TodoistGatewayException e) {
            if (isMutation(method) && e.classification == 'CONTENT') {
                throw new TodoistGatewayException('AMBIGUOUS_WRITE',
                    "Todoist ${method} response could not be consumed after the mutation was sent", e)
            }
            throw e
        } catch (Exception e) {
            throw new TodoistGatewayException(isMutation(method) ? 'AMBIGUOUS_WRITE' : 'TRANSPORT',
                "Todoist ${method} request failed", e)
        } finally {
            token = null
        }
    }

    private String readBounded(InputStream input) {
        input.withCloseable { stream ->
            ByteArrayOutputStream out = new ByteArrayOutputStream((int) Math.min(maxResponseBytes, 8192L))
            byte[] buffer = new byte[8192]
            long total = 0L
            int count
            while ((count = stream.read(buffer)) != -1) {
                total += count
                if (total > maxResponseBytes) {
                    throw new TodoistGatewayException('CONTENT', 'Todoist response exceeded max_response_bytes')
                }
                out.write(buffer, 0, count)
            }
            out.toString(StandardCharsets.UTF_8)
        }
    }

    private static boolean isMutation(String method) {
        method in ['POST', 'PUT', 'PATCH', 'DELETE']
    }

    private String resolveToken() {
        String token
        try { token = secretResolver.apply(tokenEnv) } catch (Exception e) {
            throw new TodoistGatewayException('AUTHENTICATION', 'Todoist credential could not be resolved', e)
        }
        if (!token?.trim()) throw new TodoistGatewayException('AUTHENTICATION', 'Todoist credential is unavailable')
        token.trim()
    }

    private URI resolve(String path, String query) {
        String base = baseUri.toString().replaceAll('/+$', '')
        URI.create(base + path + (query ? '?' + query : ''))
    }

    private static void requireSuccess(GatewayResponse response, String operation) {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new TodoistGatewayException('HTTP_STATUS',
                "Todoist ${operation} failed with HTTP ${response.statusCode()}")
        }
    }

    private static URI validateBaseUri(URI uri, boolean allowHttp) {
        String scheme = uri.scheme?.toLowerCase(Locale.ROOT)
        if (!uri.host || !(scheme == 'https' || (allowHttp && scheme == 'http'))) {
            throw new IllegalArgumentException('Todoist base_url must be an absolute HTTPS URL')
        }
        if (uri.userInfo || uri.fragment || uri.query) {
            throw new IllegalArgumentException('Todoist base_url must not contain user-info, query, or fragment')
        }
        uri
    }

    private static String segment(String value) { URLEncoder.encode(value, StandardCharsets.UTF_8).replace('+', '%20') }
    private static String queryValue(String value) { URLEncoder.encode(value, StandardCharsets.UTF_8) }
    private static List<Map> immutableCopies(List<Map> rows) {
        Collections.unmodifiableList(rows.collect { Collections.unmodifiableMap(new LinkedHashMap(it)) })
    }

    static final class TodoistGatewayException extends RuntimeException {
        final String classification
        TodoistGatewayException(String classification, String message, Throwable cause = null) {
            super(message, cause); this.classification = classification
        }
    }

    private static final class GatewayResponse {
        final int status
        final String content
        GatewayResponse(int status, String content) { this.status = status; this.content = content }
        int statusCode() { status }
        String body() { content }
    }
}
