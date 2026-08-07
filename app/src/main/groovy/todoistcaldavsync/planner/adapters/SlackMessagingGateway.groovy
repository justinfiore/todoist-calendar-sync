package todoistcaldavsync.planner.adapters

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import todoistcaldavsync.planner.domain.DeliveryReceipt
import todoistcaldavsync.planner.domain.Message
import todoistcaldavsync.planner.util.RetryAfter

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import java.util.Locale
import java.util.function.Function
import java.util.function.Supplier

/**
 * Slack adapter. Secrets resolved from env/secret names only — never persisted/logged.
 * Injectable transport for tests (no network). Strict HTTPS host allowlist by default.
 */
class SlackMessagingGateway implements MessagingWriteGateway {
    static final String MODE_WEBHOOK = 'webhook'
    static final String MODE_CHAT_API = 'chat_api'
    static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10)
    static final long DEFAULT_MAX_REQUEST_BYTES = 32_768L
    static final long DEFAULT_MAX_RESPONSE_BYTES = 65_536L
    static final int SLACK_TEXT_LIMIT = 3000
    static final int SLACK_SECTION_TEXT_LIMIT = 3000
    /** Webhook mode: only hooks.slack.com. chat_api: only slack.com with /api/ path. */
    static final String WEBHOOK_HOST = 'hooks.slack.com'
    static final String CHAT_API_HOST = 'slack.com'
    static final String CHAT_API_PATH_PREFIX = '/api/'
    static final String DEFAULT_CHAT_API_URL = 'https://slack.com/api/chat.postMessage'
    static final Set<String> DEFAULT_ALLOWED_HOSTS = Collections.unmodifiableSet(
        new LinkedHashSet<>([WEBHOOK_HOST, CHAT_API_HOST]))

    private final String mode
    private final String destination
    private final String webhookUrlEnv
    private final String botTokenEnv
    private final String channelId
    private final Duration timeout
    private final long maxRequestBytes
    private final long maxResponseBytes
    private final Set<String> allowedHosts
    private final boolean allowNonDefaultPort
    private final Function<HttpCall, HttpResult> transport
    private final Function<String, String> envLookup
    private final Supplier<Instant> clock
    private final String webhookUrlOverride
    private final String botTokenOverride
    private final String chatApiUrlOverride

    SlackMessagingGateway(Map opts = [:]) {
        this.mode = (opts.mode ?: MODE_WEBHOOK).toString().toLowerCase(Locale.ROOT)
        if (!(this.mode in [MODE_WEBHOOK, MODE_CHAT_API] as Set)) {
            throw new IllegalArgumentException("Slack mode must be webhook or chat_api, got: ${this.mode}")
        }
        this.destination = opts.destination?.toString()
        this.webhookUrlEnv = opts.webhookUrlEnv?.toString() ?: opts.webhook_url_env?.toString()
        this.botTokenEnv = opts.botTokenEnv?.toString() ?: opts.bot_token_env?.toString()
        this.channelId = opts.channelId?.toString() ?: opts.channel_id?.toString() ?: opts.destination?.toString()
        Duration t = opts.timeout instanceof Duration ? (Duration) opts.timeout : DEFAULT_TIMEOUT
        if (t == null || t.isZero() || t.isNegative() || t.toMillis() > Duration.ofMinutes(10).toMillis()) {
            throw new IllegalArgumentException("timeout must be positive up to 10 minutes, got: ${t}")
        }
        this.timeout = t
        long maxReq = opts.maxRequestBytes != null ? opts.maxRequestBytes as long : DEFAULT_MAX_REQUEST_BYTES
        long maxResp = opts.maxResponseBytes != null ? opts.maxResponseBytes as long : DEFAULT_MAX_RESPONSE_BYTES
        if (maxReq <= 0L || maxResp <= 0L) {
            throw new IllegalArgumentException('maxRequestBytes and maxResponseBytes must be positive')
        }
        this.maxRequestBytes = maxReq
        this.maxResponseBytes = maxResp
        Set<String> hosts = new LinkedHashSet<>()
        def allow = opts.allowedHosts ?: opts.allowed_hosts
        if (allow instanceof Collection) {
            allow.each { hosts << it.toString().toLowerCase(Locale.ROOT) }
        } else {
            hosts.addAll(modeDefaultHosts(this.mode))
        }
        this.allowedHosts = Collections.unmodifiableSet(hosts)
        this.allowNonDefaultPort = opts.allowNonDefaultPort == true || opts.allow_non_default_port == true
        this.transport = coerceHttpTransport(opts.transport)
            ?: defaultTransport(this.timeout, this.maxResponseBytes)
        this.envLookup = coerceStringFunction(opts.envResolver)
            ?: ({ String name -> System.getenv(name) } as Function)
        this.clock = coerceInstantSupplier(opts.clock)
            ?: ({ Instant.now() } as Supplier)
        this.webhookUrlOverride = opts.webhookUrlOverride?.toString()
        this.botTokenOverride = opts.botTokenOverride?.toString()
        this.chatApiUrlOverride = opts.chatApiUrlOverride?.toString() ?: opts.chat_api_url_override?.toString()
        if (this.mode == MODE_WEBHOOK && !this.webhookUrlEnv && !this.webhookUrlOverride) {
            throw new IllegalArgumentException('webhook_url_env is required for webhook mode')
        }
        if (this.mode == MODE_CHAT_API && !this.botTokenEnv && !this.botTokenOverride) {
            throw new IllegalArgumentException('bot_token_env is required for chat_api mode')
        }
    }

    private static Set<String> modeDefaultHosts(String mode) {
        if (mode == MODE_WEBHOOK) {
            return [WEBHOOK_HOST] as Set
        }
        return [CHAT_API_HOST] as Set
    }

    Duration getTimeout() { timeout }
    long getMaxRequestBytes() { maxRequestBytes }
    long getMaxResponseBytes() { maxResponseBytes }
    Set<String> getAllowedHosts() { allowedHosts }
    String getMode() { mode }

    /**
     * Coerce Groovy Closure or Function into Function&lt;HttpCall, HttpResult&gt;.
     * Plain {@code instanceof Function} is false for Groovy closures.
     */
    private static Function<HttpCall, HttpResult> coerceHttpTransport(def raw) {
        if (raw == null) {
            return null
        }
        if (raw instanceof Function) {
            return (Function<HttpCall, HttpResult>) raw
        }
        return { HttpCall call -> raw.call(call) } as Function
    }

    private static Function<String, String> coerceStringFunction(def raw) {
        if (raw == null) {
            return null
        }
        if (raw instanceof Function) {
            return (Function<String, String>) raw
        }
        return { String name -> raw.call(name) } as Function
    }

    private static Supplier<Instant> coerceInstantSupplier(def raw) {
        if (raw == null) {
            return null
        }
        if (raw instanceof Supplier) {
            return (Supplier<Instant>) raw
        }
        return { (Instant) raw.call() } as Supplier
    }

    @Override
    DeliveryReceipt send(Message message) {
        if (message == null) {
            throw new IllegalArgumentException('message is required')
        }
        Instant attempted = clock.get()
        String dest = message.destination ?: destination ?: channelId
        try {
            String payload = buildPayload(message, dest)
            enforceRequestSize(payload)
            HttpCall call = buildCall(payload, dest)
            validateEndpoint(call.uri)
            HttpResult result
            try {
                result = transport.apply(call)
            } catch (MessagingGatewayException e) {
                return failedReceipt(message, dest, attempted, e.classification, scrub(e.message))
            } catch (Exception e) {
                Throwable root = unwrap(e)
                String cls = classifyTransport(root)
                return failedReceipt(message, dest, attempted, cls, scrub(root?.message ?: e.message))
            }
            if (result == null) {
                return failedReceipt(message, dest, attempted, 'TRANSPORT', 'Slack transport returned null')
            }
            if (result.statusCode == 429) {
                Long ra = parseRetryAfterSeconds(result.headers, attempted)
                return failedReceipt(message, dest, attempted, 'RATE_LIMIT',
                    "Slack HTTP 429${ra != null ? " retry-after=${ra}s" : ''}",
                    [retryAfterSeconds: ra, httpStatus: 429])
            }
            if (result.statusCode < 200 || result.statusCode >= 300) {
                String cls = result.statusCode >= 500 ? 'HTTP_5XX' : 'HTTP_4XX'
                return failedReceipt(message, dest, attempted, cls,
                    "Slack HTTP ${result.statusCode}: ${truncate(scrub(result.body), 200)}",
                    [httpStatus: result.statusCode])
            }
            enforceBodySize(result.body)
            return parseSuccess(message, dest, attempted, result)
        } catch (MessagingGatewayException e) {
            return failedReceipt(message, dest, attempted, e.classification, scrub(e.message))
        } catch (Exception e) {
            return failedReceipt(message, dest, attempted, 'UNKNOWN', scrub(e.message))
        }
    }

    String buildPayload(Message message, String dest) {
        String text = escapeMrkdwn(message.body ?: '')
        text = truncateWithOmitted(text, SLACK_TEXT_LIMIT)
        if (mode == MODE_WEBHOOK) {
            Map payload = [
                text  : text,
                blocks: buildBlocks(message, text)
            ]
            if (message.subject) {
                payload.username = truncateWithOmitted(escapeMrkdwn(message.subject), 80)
            }
            return JsonOutput.toJson(payload)
        }
        Map payload = [
            channel: dest ?: channelId,
            text   : text,
            blocks : buildBlocks(message, text),
            mrkdwn : true
        ]
        if (message.metadata?.threadTs) {
            payload.thread_ts = message.metadata.threadTs.toString()
        }
        return JsonOutput.toJson(payload)
    }

    private List buildBlocks(Message message, String text) {
        List blocks = []
        if (message.subject) {
            String header = truncateWithOmitted(escapeMrkdwn(message.subject), 150)
            blocks << [
                type: 'header',
                text: [type: 'plain_text', text: header, emoji: true]
            ]
        }
        String remaining = text
        int parts = 0
        while (remaining != null && remaining.length() > 0 && parts < 40) {
            String chunk
            if (remaining.length() <= SLACK_SECTION_TEXT_LIMIT) {
                chunk = remaining
                remaining = ''
            } else {
                int cut = remaining.lastIndexOf('\n', SLACK_SECTION_TEXT_LIMIT)
                if (cut < SLACK_SECTION_TEXT_LIMIT / 2) {
                    cut = SLACK_SECTION_TEXT_LIMIT
                }
                chunk = remaining.substring(0, cut)
                remaining = remaining.substring(cut)
            }
            blocks << [
                type: 'section',
                text: [type: 'mrkdwn', text: chunk]
            ]
            parts++
        }
        if (remaining && remaining.length() > 0) {
            int omitted = remaining.length()
            blocks << [
                type    : 'context',
                elements: [[type: 'mrkdwn', text: "_…(${omitted} chars omitted)_"]]
            ]
        }
        List metaBits = []
        if (message.planId) {
            metaBits << "plan=${escapeAndBoundMeta(message.planId)}"
        }
        if (message.planVersion != null) {
            metaBits << "v=${escapeAndBoundMeta(String.valueOf(message.planVersion))}"
        }
        if (message.planHash) {
            String hp = message.planHash.length() > 12 ? message.planHash.substring(0, 12) : message.planHash
            metaBits << "hash=${escapeAndBoundMeta(hp)}"
        }
        if (message.proposalId) {
            metaBits << "proposal=${escapeAndBoundMeta(message.proposalId)}"
        }
        if (message.kind) {
            metaBits << "kind=${escapeAndBoundMeta(message.kind)}"
        }
        if (message.destination) {
            metaBits << "destination=${escapeAndBoundMeta(message.destination)}"
        }
        if (message.metadata?.threadTs) {
            metaBits << "thread=${escapeAndBoundMeta(message.metadata.threadTs.toString())}"
        }
        if (metaBits) {
            String metaText = truncateWithOmitted(metaBits.join(' · '), 2000)
            blocks << [
                type    : 'context',
                elements: [[type: 'mrkdwn', text: metaText]]
            ]
        }
        return blocks
    }

    /**
     * Escape mrkdwn and bound length for context metadata values so hostile IDs
     * ({@code <@U>}, {@code <!channel>}, {@code &}, {@code *}, backticks/newlines) cannot inject.
     */
    static String escapeAndBoundMeta(String raw) {
        if (raw == null) {
            return ''
        }
        String escaped = escapeMrkdwn(raw.replace('\r', ' ').replace('\n', ' '))
        return truncateWithOmitted(escaped, 120)
    }

    HttpCall buildCall(String payload, String dest) {
        if (mode == MODE_WEBHOOK) {
            String url = resolveSecret(webhookUrlEnv, webhookUrlOverride, 'webhook URL')
            URI uri = URI.create(url)
            return new HttpCall(uri, 'POST',
                ['Content-Type': 'application/json; charset=utf-8'],
                payload, null)
        }
        String token = resolveSecret(botTokenEnv, botTokenOverride, 'bot token')
        String apiUrl = chatApiUrlOverride ?: DEFAULT_CHAT_API_URL
        URI uri = URI.create(apiUrl)
        return new HttpCall(uri, 'POST',
            [
                'Content-Type' : 'application/json; charset=utf-8',
                'Authorization': "Bearer ${token}"
            ],
            payload, redactToken(token))
    }

    /**
     * Mode-specific endpoint validation (SSRF-hardened).
     * webhook: HTTPS, host exactly hooks.slack.com, default port 443 only.
     * chat_api: HTTPS, host exactly slack.com, path prefix /api/, default port 443 only.
     * Rejects userinfo, lookalike hosts, subdomains, non-443 ports (unless test override).
     */
    void validateEndpoint(URI uri) {
        if (uri == null) {
            throw new MessagingGatewayException('ENDPOINT', 'Slack endpoint URI is required')
        }
        String scheme = uri.scheme?.toLowerCase(Locale.ROOT)
        if (scheme != 'https') {
            throw new MessagingGatewayException('ENDPOINT',
                "Slack endpoint must use HTTPS, got: ${scheme ?: 'null'}")
        }
        if (uri.userInfo != null && !uri.userInfo.isEmpty()) {
            throw new MessagingGatewayException('ENDPOINT', 'Slack endpoint must not include userinfo')
        }
        String host = uri.host?.toLowerCase(Locale.ROOT)
        if (!host) {
            throw new MessagingGatewayException('ENDPOINT', 'Slack host is required')
        }
        int port = uri.port
        if (port != -1 && port != 443 && !allowNonDefaultPort) {
            throw new MessagingGatewayException('ENDPOINT',
                "Slack endpoint must use default HTTPS port 443, got: ${port}")
        }
        if (mode == MODE_WEBHOOK) {
            if (host != WEBHOOK_HOST || !allowedHosts.contains(host)) {
                throw new MessagingGatewayException('ENDPOINT',
                    "Slack webhook host must be exactly ${WEBHOOK_HOST}, got: ${host}")
            }
            return
        }
        // chat_api: host slack.com + path /api/
        if (host != CHAT_API_HOST || !allowedHosts.contains(host)) {
            throw new MessagingGatewayException('ENDPOINT',
                "Slack chat_api host must be exactly ${CHAT_API_HOST}, got: ${host}")
        }
        String path = uri.path ?: ''
        if (!path.startsWith(CHAT_API_PATH_PREFIX)) {
            throw new MessagingGatewayException('ENDPOINT',
                "Slack chat_api path must start with ${CHAT_API_PATH_PREFIX}, got: ${path ?: '(empty)'}")
        }
    }

    private String resolveSecret(String envName, String override, String label) {
        if (override) {
            return override
        }
        if (!envName) {
            throw new MessagingGatewayException('CONFIG', "Missing env reference for ${label}")
        }
        String val = envLookup != null ? envLookup.apply(envName) : System.getenv(envName)
        if (val == null || val.trim().isEmpty()) {
            throw new MessagingGatewayException('CONFIG',
                "Environment variable '${envName}' for ${label} is not set")
        }
        return val
    }

    /**
     * Strict Slack success contract.
     * webhook: 2xx + body trimmed equals "ok" (case-insensitive) only. Empty, whitespace-only,
     * other text, JSON object, or malformed never counts as DELIVERED.
     * chat_api: 2xx + valid JSON object with ok:true only. ok:false is PROVIDER failure;
     * empty/malformed/non-object fail without delivery.
     */
    private DeliveryReceipt parseSuccess(Message message, String dest, Instant attempted, HttpResult result) {
        String body = result.body
        if (mode == MODE_WEBHOOK) {
            return parseWebhookSuccess(message, dest, attempted, result, body)
        }
        return parseChatApiSuccess(message, dest, attempted, result, body)
    }

    private DeliveryReceipt parseWebhookSuccess(Message message, String dest, Instant attempted,
                                                HttpResult result, String body) {
        if (body == null) {
            return failedReceipt(message, dest, attempted, 'WEBHOOK_BODY',
                'Slack webhook response body is null (not plain ok)',
                [httpStatus: result.statusCode, responseClass: 'null_body'])
        }
        String trimmed = body.trim()
        if (trimmed.isEmpty()) {
            return failedReceipt(message, dest, attempted, 'WEBHOOK_BODY',
                'Slack webhook response empty/whitespace (not plain ok)',
                [httpStatus: result.statusCode, responseClass: 'empty'])
        }
        if (trimmed.equalsIgnoreCase('ok')) {
            return delivered(message, dest, attempted, result, null, null, dest)
        }
        // Any other text including JSON objects is never success in webhook mode
        String responseClass = 'other_text'
        if (trimmed.startsWith('{') || trimmed.startsWith('[')) {
            responseClass = 'json'
        } else if (trimmed.toLowerCase(Locale.ROOT).contains('<html')) {
            responseClass = 'html'
        }
        return failedReceipt(message, dest, attempted, 'WEBHOOK_BODY',
            "Slack webhook response not plain ok: ${truncate(scrub(trimmed), 80)}",
            [httpStatus: result.statusCode, responseClass: responseClass])
    }

    private DeliveryReceipt parseChatApiSuccess(Message message, String dest, Instant attempted,
                                                HttpResult result, String body) {
        if (body == null || body.trim().isEmpty()) {
            return failedReceipt(message, dest, attempted, 'MALFORMED_JSON',
                'Slack chat_api response body empty',
                [httpStatus: result.statusCode, responseClass: 'empty'])
        }
        Object parsed
        try {
            parsed = new JsonSlurper().parseText(body)
        } catch (Exception e) {
            return failedReceipt(message, dest, attempted, 'MALFORMED_JSON',
                "Slack response is not valid JSON: ${scrub(e.message)}",
                [httpStatus: result.statusCode, responseClass: 'malformed_json'])
        }
        if (!(parsed instanceof Map)) {
            return failedReceipt(message, dest, attempted, 'SCHEMA',
                'Slack chat_api response root must be object',
                [httpStatus: result.statusCode, responseClass: 'non_object'])
        }
        Map map = parsed as Map
        if (!map.containsKey('ok')) {
            return failedReceipt(message, dest, attempted, 'SCHEMA',
                'Slack chat_api response missing ok field',
                [httpStatus: result.statusCode, responseClass: 'missing_ok'])
        }
        // Strict: JSON boolean true only. String "true", number 1, truthy values are SCHEMA failures.
        def okVal = map.ok
        if (okVal == false) {
            String err = map.error?.toString() ?: 'unknown_error'
            return failedReceipt(message, dest, attempted, 'PROVIDER',
                "Slack API error: ${scrub(err)}",
                [httpStatus: result.statusCode, responseClass: 'ok_false', slackError: scrub(err)])
        }
        if (okVal != true) {
            return failedReceipt(message, dest, attempted, 'SCHEMA',
                "Slack chat_api ok field not boolean true: ${truncate(String.valueOf(okVal), 40)}",
                [httpStatus: result.statusCode, responseClass: 'ok_invalid', okType: okVal?.getClass()?.simpleName])
        }
        return delivered(message, dest, attempted, result,
            map.ts?.toString() ?: (map.message instanceof Map ? map.message.ts?.toString() : null),
            map.message instanceof Map ? map.message.thread_ts?.toString() : map.ts?.toString(),
            map.channel?.toString() ?: dest)
    }

    private DeliveryReceipt delivered(Message message, String dest, Instant attempted, HttpResult result,
                                      String providerMessageId, String threadId, String channel) {
        DeliveryReceipt.builder()
            .id(receiptId(message, attempted))
            .idempotencyKey(message.idempotencyKey)
            .kind(message.kind)
            .destination(dest)
            .planId(message.planId)
            .planVersion(message.planVersion)
            .planHash(message.planHash)
            .proposalId(message.proposalId)
            .status('DELIVERED')
            .providerMessageId(providerMessageId)
            .threadId(threadId)
            .channelId(channel)
            .attemptedAt(attempted)
            .completedAt(clock.get())
            .metadata([httpStatus: result.statusCode, mode: mode])
            .build()
    }

    private static DeliveryReceipt failedReceipt(Message message, String dest, Instant attempted,
                                                 String classification, String errMsg,
                                                 Map extraMeta = [:]) {
        Map meta = new LinkedHashMap<>(extraMeta ?: [:])
        meta.mode = meta.mode ?: 'slack'
        DeliveryReceipt.builder()
            .id(receiptId(message, attempted))
            .idempotencyKey(message.idempotencyKey)
            .kind(message.kind)
            .destination(dest)
            .planId(message.planId)
            .planVersion(message.planVersion)
            .planHash(message.planHash)
            .proposalId(message.proposalId)
            .status('FAILED')
            .attemptedAt(attempted)
            .completedAt(attempted)
            .errorClassification(classification)
            .errorMessage(truncate(errMsg ?: 'unknown', 400))
            .metadata(meta)
            .build()
    }

    private static String receiptId(Message message, Instant attempted) {
        String base = message.idempotencyKey ?: 'msg'
        String safe = base.replaceAll(/[^A-Za-z0-9._-]/, '_')
        if (safe.length() > 40) {
            safe = safe.substring(0, 40)
        }
        return "dlv-${safe}-${attempted.toEpochMilli()}"
    }

    static String escapeMrkdwn(String raw) {
        if (raw == null) {
            return ''
        }
        return raw
            .replace('&', '&amp;')
            .replace('<', '&lt;')
            .replace('>', '&gt;')
    }

    static String truncateWithOmitted(String text, int max) {
        if (text == null) {
            return ''
        }
        if (text.length() <= max) {
            return text
        }
        String markerPrefix = '\n…('
        String markerSuffix = ' chars omitted)'
        int omitted = text.length()
        for (int guard = 0; guard < 8; guard++) {
            String marker = markerPrefix + omitted + markerSuffix
            int keep = max - marker.length()
            if (keep < 16) {
                keep = Math.max(0, max - 20)
                return text.substring(0, keep) + '…(omitted)'
            }
            if (text.length() <= keep) {
                return text
            }
            int newOmitted = text.length() - keep
            if (newOmitted == omitted) {
                return text.substring(0, keep) + marker
            }
            omitted = newOmitted
        }
        String marker = markerPrefix + omitted + markerSuffix
        int keep = Math.max(0, max - marker.length())
        return text.substring(0, keep) + marker
    }

    /**
     * Parse Retry-After: delta-seconds or RFC 7231 HTTP-date.
     * Past dates clamp to 0; excessive values clamp to {@link RetryAfter#DEFAULT_MAX_SECONDS}.
     * Malformed → null. Case-insensitive header name.
     */
    static Long parseRetryAfterSeconds(Map<String, List<String>> headers, Instant now = Instant.now()) {
        return RetryAfter.parseSeconds(headers, now != null ? now : Instant.now())
    }

    private void enforceRequestSize(String payload) {
        if (payload == null) {
            return
        }
        long size = payload.getBytes(StandardCharsets.UTF_8).length
        if (size > maxRequestBytes) {
            throw new MessagingGatewayException('CONTENT',
                "Slack request exceeds max size (${size} > ${maxRequestBytes} bytes)")
        }
    }

    private void enforceBodySize(String body) {
        if (body == null) {
            return
        }
        long size = body.getBytes(StandardCharsets.UTF_8).length
        if (size > maxResponseBytes) {
            throw new MessagingGatewayException('CONTENT',
                "Slack response exceeds max size (${size} > ${maxResponseBytes} bytes)")
        }
    }

    static String scrub(String raw) {
        if (raw == null) {
            return ''
        }
        String s = raw
            .replaceAll(/(?i)(Bearer\s+)[A-Za-z0-9._\-]+/, '$1***')
            .replaceAll(/(?i)(xox[baprs]-[A-Za-z0-9-]+)/, '***')
            .replaceAll(/(?i)(hooks\.slack\.com\/services\/)[A-Za-z0-9\/_-]+/, '$1***')
            .replaceAll(/(?i)([?&](token|api_key|apikey|secret|password|auth)=)[^&\s]*/, '$1***')
            .replaceAll(/\?[^\s]*/, '?…')
        return truncate(s, 240)
    }

    private static String redactToken(String token) {
        if (!token) {
            return null
        }
        if (token.length() <= 8) {
            return '***'
        }
        return token.substring(0, 4) + '…***'
    }

    private static String classifyTransport(Throwable t) {
        String type = t?.getClass()?.simpleName ?: ''
        String msg = (t?.message ?: '').toLowerCase(Locale.ROOT)
        if (type.toLowerCase(Locale.ROOT).contains('timeout') || msg.contains('timed out') || msg.contains('timeout')) {
            return 'TIMEOUT'
        }
        return 'TRANSPORT'
    }

    static Throwable unwrap(Throwable t) {
        Throwable cur = t
        int guard = 0
        while (cur != null && guard++ < 8) {
            if (cur instanceof java.lang.reflect.UndeclaredThrowableException && cur.cause != null) {
                cur = cur.cause
                continue
            }
            if (cur instanceof java.lang.reflect.InvocationTargetException && cur.cause != null) {
                cur = cur.cause
                continue
            }
            if (cur instanceof RuntimeException &&
                cur.getClass().name == 'org.codehaus.groovy.runtime.InvokerInvocationException' &&
                cur.cause != null) {
                cur = cur.cause
                continue
            }
            break
        }
        return cur ?: t
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return ''
        }
        s.length() <= max ? s : s.substring(0, max) + '…'
    }

    static HttpRequest buildHttpRequest(HttpCall call, Duration timeout) {
        if (call == null || call.uri == null) {
            throw new IllegalArgumentException('call.uri is required')
        }
        Duration t = timeout != null ? timeout : DEFAULT_TIMEOUT
        HttpRequest.Builder b = HttpRequest.newBuilder(call.uri).timeout(t)
        call.headers?.each { k, v ->
            if (k && v != null) {
                b.header(k, v)
            }
        }
        byte[] bodyBytes = (call.body ?: '').getBytes(StandardCharsets.UTF_8)
        if ((call.method ?: 'POST').equalsIgnoreCase('GET')) {
            return b.GET().build()
        }
        return b.POST(HttpRequest.BodyPublishers.ofByteArray(bodyBytes)).build()
    }

    /**
     * Build the default JDK {@link HttpClient}: {@link HttpClient.Redirect#NEVER} and a finite
     * connect timeout. Package-visible seam for tests (no network, no secrets).
     */
    static HttpClient buildHttpClient(Duration timeout) {
        Duration t = timeout != null ? timeout : DEFAULT_TIMEOUT
        if (t.isZero() || t.isNegative()) {
            throw new IllegalArgumentException("connect timeout must be positive, got: ${t}")
        }
        return HttpClient.newBuilder()
            .connectTimeout(t)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build()
    }

    private static Function<HttpCall, HttpResult> defaultTransport(Duration timeout, long maxResponseBytes) {
        HttpClient client = buildHttpClient(timeout)
        return { HttpCall call ->
            HttpRequest req = buildHttpRequest(call, timeout)
            HttpResponse<String> resp = client.send(req, boundedBodyHandler(maxResponseBytes))
            Map<String, List<String>> headers = new LinkedHashMap<>()
            resp.headers()?.map()?.each { k, v -> headers[k] = new ArrayList<>(v) }
            new HttpResult(resp.statusCode(), resp.body(), headers)
        } as Function
    }

    static HttpResponse.BodyHandler<String> boundedBodyHandler(long maxBytes) {
        if (maxBytes <= 0L) {
            throw new IllegalArgumentException("maxBytes must be positive, got: ${maxBytes}")
        }
        return { HttpResponse.ResponseInfo info ->
            OptionalLong cl = info.headers().firstValueAsLong('Content-Length')
            if (cl.isPresent() && cl.asLong > maxBytes) {
                return HttpResponse.BodySubscribers.mapping(
                    HttpResponse.BodySubscribers.discarding(),
                    { ignored ->
                        throw new MessagingGatewayException('CONTENT',
                            "Slack Content-Length ${cl.asLong} exceeds max size ${maxBytes} bytes")
                    } as Function)
            }
            HttpResponse.BodySubscriber<InputStream> streamSub =
                HttpResponse.BodySubscribers.ofInputStream()
            return HttpResponse.BodySubscribers.mapping(streamSub, { InputStream inStream ->
                try {
                    return readBounded(inStream, maxBytes)
                } finally {
                    try {
                        inStream?.close()
                    } catch (Exception ignored) {
                    }
                }
            } as Function)
        } as HttpResponse.BodyHandler<String>
    }

    static String readBounded(InputStream inStream, long maxBytes) {
        if (inStream == null) {
            return ''
        }
        ByteArrayOutputStream buf = new ByteArrayOutputStream()
        byte[] chunk = new byte[8192]
        long total = 0L
        int n
        while ((n = inStream.read(chunk)) >= 0) {
            if (n == 0) {
                continue
            }
            total += n
            if (total > maxBytes) {
                throw new MessagingGatewayException('CONTENT',
                    "Slack response exceeds max size (>${maxBytes} bytes)")
            }
            buf.write(chunk, 0, n)
        }
        return new String(buf.toByteArray(), StandardCharsets.UTF_8)
    }

    static final class HttpCall {
        final URI uri
        final String method
        final Map<String, String> headers
        final String body
        final String credentialFingerprint

        HttpCall(URI uri, String method, Map<String, String> headers, String body,
                 String credentialFingerprint) {
            this.uri = uri
            this.method = method
            this.headers = headers != null
                ? Collections.unmodifiableMap(new LinkedHashMap<>(headers))
                : Collections.emptyMap()
            this.body = body
            this.credentialFingerprint = credentialFingerprint
        }
    }

    static final class HttpResult {
        final int statusCode
        final String body
        final Map<String, List<String>> headers

        HttpResult(int statusCode, String body, Map<String, List<String>> headers = [:]) {
            this.statusCode = statusCode
            this.body = body
            this.headers = headers != null
                ? Collections.unmodifiableMap(new LinkedHashMap<>(headers))
                : Collections.emptyMap()
        }
    }

    static class MessagingGatewayException extends RuntimeException {
        final String classification

        MessagingGatewayException(String classification, String message, Throwable cause = null) {
            super(message, cause)
            this.classification = classification ?: 'UNKNOWN'
        }
    }
}
