package todoistcaldavsync.planner.messaging

import com.slack.api.bolt.App
import com.slack.api.bolt.AppConfig
import com.slack.api.bolt.response.Response
import com.slack.api.model.event.AppMentionEvent
import com.slack.api.model.event.MessageEvent
import com.slack.api.socket_mode.SocketModeClient
import com.slack.api.socket_mode.SocketModeClient.Backend
import com.slack.api.bolt.socket_mode.SocketModeApp
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import todoistcaldavsync.planner.adapters.SlackMessagingGateway
import todoistcaldavsync.planner.domain.DeliveryReceipt
import todoistcaldavsync.planner.domain.Message
import todoistcaldavsync.planner.state.DeliveryLedger

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.function.Consumer

/** Slack Socket Mode inbound events plus channel/thread Web API delivery. */
final class SlackSocketModeMessagingSurface implements MessagingSurface {
    private static enum Admission { ACCEPTED, INVALID, BUSY }
    private final Map config
    private final Closure<String> envLookup
    private final Closure<Map> apiTransport
    private final ExecutorService callbacks
    private final DeliveryLedger deliveryLedger
    private final Closure<Boolean> connectionProbe
    private volatile Consumer<MessagingEvent> handler
    private volatile boolean connected
    private volatile SocketModeApp socketModeApp
    private SlackMessagingGateway outbound
    private String botToken

    SlackSocketModeMessagingSurface(Map config,
                                    Closure<String> envLookup = { String name -> System.getenv(name) },
                                    Closure<Map> apiTransport = null,
                                    ExecutorService callbackExecutor = null,
                                    DeliveryLedger deliveryLedger = null,
                                    Closure<Boolean> connectionProbe = null) {
        this.config = Collections.unmodifiableMap(new LinkedHashMap(config ?: [:]))
        this.envLookup = envLookup ?: ({ String name -> System.getenv(name) })
        this.apiTransport = apiTransport
        this.deliveryLedger = deliveryLedger
        this.connectionProbe = connectionProbe
        int queueCapacity = (config?.eventQueueCapacity ?: 100) as int
        this.callbacks = callbackExecutor ?: new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<Runnable>(queueCapacity), { Runnable r ->
                Thread t = new Thread(r, 'smartplanner-slack-events')
                t.daemon = false
                t
            } as java.util.concurrent.ThreadFactory, new ThreadPoolExecutor.AbortPolicy())
    }

    @Override
    synchronized void start(Consumer<MessagingEvent> eventHandler) {
        if (connected) return
        if (eventHandler == null) throw new IllegalArgumentException('event handler is required')
        this.handler = eventHandler
        this.botToken = requireSecret(config.botTokenEnv?.toString(), 'Slack bot token')
        String appToken = requireSecret(config.appTokenEnv?.toString(), 'Slack app-level token')
        Map outboundOptions = [mode: 'chat_api', botTokenOverride: botToken,
            destination: config.channel?.toString(), timeout: Duration.ofSeconds(10),
            maxRequestBytes: 32_768L, maxResponseBytes: 1_048_576L]
        if (apiTransport != null) {
            outboundOptions.transport = { SlackMessagingGateway.HttpCall call ->
                Map payload = new JsonSlurper().parseText(call.body) as Map
                Map result = new LinkedHashMap(apiTransport.call('chat.postMessage', payload, botToken) as Map ?: [:])
                def statusRaw = result.remove('_httpStatus')
                int status = statusRaw == null ? 200 : statusRaw as int
                new SlackMessagingGateway.HttpResult(status, JsonOutput.toJson(result))
            }
        }
        this.outbound = new SlackMessagingGateway(outboundOptions)

        App app = new App(AppConfig.builder().singleTeamBotToken(botToken).build())
        String command = config.command?.toString() ?: '/smartplanner'
        app.command(command) { req, ctx ->
            def p = req.payload
            Admission admission = admit(new MessagingEvent(
                eventId: firstNonblank(p.triggerId, "command:${p.channelId}:${p.userId}:${System.nanoTime()}"),
                type: 'command', actorId: p.userId, channelId: p.channelId,
                messageTs: null, threadTs: null, text: p.text ?: '', bot: false))
            String appName = config.appName ?: 'SmartPlanner'
            return ctx.ack(admission == Admission.ACCEPTED
                ? "${appName} accepted the request."
                : admission == Admission.INVALID
                    ? "${appName} rejected the request because it exceeds the configured event-size limit."
                    : "${appName} is busy and did not accept the request; retry shortly.")
        }
        app.event(AppMentionEvent) { req, ctx ->
            AppMentionEvent e = req.event
            Admission admission = admit(new MessagingEvent(eventId: req.payload?.eventId ?: req.context?.requestId ?: "mention:${e.channel}:${e.ts}",
                type: 'app_mention', actorId: e.user, channelId: e.channel,
                messageTs: e.ts, threadTs: e.threadTs, text: stripLeadingMention(e.text), bot: false))
            return admission == Admission.BUSY ? busyResponse() : ctx.ack()
        }
        app.event(MessageEvent) { req, ctx ->
            MessageEvent e = req.event
            Admission admission = admit(new MessagingEvent(eventId: req.payload?.eventId ?: req.context?.requestId ?: "message:${e.channel}:${e.ts}",
                type: 'thread_reply', actorId: e.user, channelId: e.channel,
                messageTs: e.ts, threadTs: e.threadTs, text: e.text ?: '',
                bot: e.botId != null || e.subtype == 'bot_message'))
            return admission == Admission.BUSY ? busyResponse() : ctx.ack()
        }
        this.socketModeApp = new SocketModeApp(appToken, Backend.JavaWebSocket, app)
        this.socketModeApp.startAsync()
        this.connected = true
    }

    @Override
    PublishedMessage publishProposal(Message message) {
        requireConnected()
        DeliveryReceipt receipt = durableSend(message)
        return fromReceipt(receipt)
    }

    @Override
    PublishedMessage reply(String channelId, String threadTs, String text, String idempotencyKey) {
        requireConnected()
        Message message = Message.builder()
            .kind('approval_status')
            .subject(null)
            .body(text ?: '')
            .destination(channelId ?: config.channel?.toString())
            .idempotencyKey(idempotencyKey)
            .createdAt(Instant.now())
            .metadata([threadTs: threadTs])
            .build()
        return fromReceipt(durableSend(message))
    }

    @Override
    void setWorkingStatus(String channelId, String threadTs, String status, List<String> loadingMessages) {
        if (!channelId || !threadTs) return
        Map payload = [channel_id: channelId, thread_ts: threadTs, status: status ?: '']
        if (loadingMessages) payload.loading_messages = loadingMessages
        Map result = callApi('assistant.threads.setStatus', payload)
        if (result.ok != true) {
            throw new SlackSurfaceException(result.error?.toString() ?: 'Slack status API rejected request')
        }
    }

    @Override
    void clearWorkingStatus(String channelId, String threadTs) {
        setWorkingStatus(channelId, threadTs, '', [])
    }

    @Override
    boolean isConnected() {
        if (!connected) return false
        if (connectionProbe != null) {
            try { return connectionProbe.call() == true }
            catch (Throwable ignored) { return false }
        }
        SocketModeApp app = socketModeApp
        if (app == null) return true // injected outbound-only test seam
        try {
            return !app.isClientStopped() && app.client != null && app.client.verifyConnection()
        } catch (Throwable ignored) {
            return false
        }
    }

    @Override
    synchronized void close() {
        connected = false
        if (socketModeApp != null) {
            try { socketModeApp.stop() } catch (Exception ignored) {}
            socketModeApp = null
        }
        callbacks.shutdown()
        try { callbacks.awaitTermination(5, TimeUnit.SECONDS) } catch (InterruptedException e) { Thread.currentThread().interrupt() }
    }

    boolean enqueue(MessagingEvent event) {
        admit(event) == Admission.ACCEPTED
    }

    private Admission admit(MessagingEvent event) {
        if (event == null) return Admission.INVALID
        int maxChars = (config.maxEventTextChars ?: 4000) as int
        if ((event.text ?: '').length() > maxChars) {
            System.err.println("SmartPlanner ignored oversized Slack event ${event.eventId ?: 'unknown'}")
            return Admission.INVALID
        }
        try {
            callbacks.submit {
                for (int attempt = 1; attempt <= 3; attempt++) {
                    try {
                        handler?.accept(event)
                        return
                    } catch (Throwable t) {
                        System.err.println("SmartPlanner Slack event attempt ${attempt} failed: ${t.class.simpleName}: ${t.message}")
                        if (attempt == 3) return
                        try { Thread.sleep(100L * attempt) }
                        catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt()
                            return
                        }
                    }
                }
            }
            return Admission.ACCEPTED
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            System.err.println("SmartPlanner Slack event queue is full; event ${event.eventId ?: 'unknown'} was not accepted")
            return Admission.BUSY
        }
    }

    private static Response busyResponse() {
        Response.builder().statusCode(503).body('SmartPlanner callback queue is full; retry this event.').build()
    }

    private DeliveryReceipt durableSend(Message message) {
        if (deliveryLedger == null) return outbound.send(message)
        Instant attempted = Instant.now()
        DeliveryReceipt pending = DeliveryReceipt.builder()
            .id("pending-${UUID.randomUUID()}").idempotencyKey(message.idempotencyKey)
            .kind(message.kind).destination(message.destination).planId(message.planId)
            .planVersion(message.planVersion).planHash(message.planHash).proposalId(message.proposalId)
            .status('PENDING').attemptedAt(attempted).metadata([transport: 'slack_socket_mode'])
            .build()
        def claim = deliveryLedger.tryClaimPending(message.idempotencyKey, pending)
        if (!claim.claimed) {
            if (claim.existing?.status == 'DELIVERED') return claim.existing
            throw new SlackSurfaceException("Delivery ${message.idempotencyKey} is not safely resendable: ${claim.reason}")
        }
        try {
            DeliveryReceipt result = outbound.send(message)
            if (result.status == 'DELIVERED') {
                try {
                    return deliveryLedger.recordDelivered(result)
                } catch (Throwable ledgerFailure) {
                    DeliveryReceipt unknown = copyReceipt(result, 'UNKNOWN', 'LEDGER_FINALIZATION',
                        'Provider accepted message but durable finalization failed; reconciliation required')
                    try { deliveryLedger.transition(message.idempotencyKey, ['PENDING', 'ATTEMPT'] as Set, unknown) }
                    catch (Throwable ignored) {}
                    throw new SlackSurfaceException('Slack delivery succeeded but ledger finalization is unknown; refusing blind resend', ledgerFailure)
                }
            }
            Set<String> ambiguous = ['TIMEOUT', 'TRANSPORT', 'UNKNOWN', 'INTERRUPTED'] as Set
            DeliveryReceipt failed = ambiguous.contains(result.errorClassification)
                ? copyReceipt(result, 'UNKNOWN', 'AMBIGUOUS_DELIVERY',
                    'Slack delivery outcome is unknown; reconcile the channel before retrying')
                : (result.status == 'FAILED' ? result : copyReceipt(result, 'FAILED',
                    result.errorClassification ?: 'PROVIDER', result.errorMessage ?: 'Slack delivery failed'))
            deliveryLedger.transition(message.idempotencyKey, ['PENDING', 'ATTEMPT'] as Set, failed)
            return failed
        } catch (SlackSurfaceException e) {
            throw e
        } catch (Throwable t) {
            DeliveryReceipt failed = DeliveryReceipt.builder()
                .id("failed-${UUID.randomUUID()}").idempotencyKey(message.idempotencyKey)
                .kind(message.kind).destination(message.destination).planId(message.planId)
                .planVersion(message.planVersion).planHash(message.planHash).proposalId(message.proposalId)
                .status('FAILED').attemptedAt(attempted).completedAt(Instant.now())
                .errorClassification('TRANSPORT').errorMessage(t.message ?: t.class.simpleName).build()
            try { deliveryLedger.transition(message.idempotencyKey, ['PENDING', 'ATTEMPT'] as Set, failed) }
            catch (Throwable ignored) {}
            throw new SlackSurfaceException('Slack delivery failed', t)
        }
    }

    private static DeliveryReceipt copyReceipt(DeliveryReceipt source, String status,
                                               String classification, String error) {
        DeliveryReceipt.builder().id("${status.toLowerCase(Locale.ROOT)}-${UUID.randomUUID()}")
            .idempotencyKey(source.idempotencyKey).kind(source.kind).destination(source.destination)
            .planId(source.planId).planVersion(source.planVersion).planHash(source.planHash)
            .proposalId(source.proposalId).status(status).providerMessageId(source.providerMessageId)
            .threadId(source.threadId).channelId(source.channelId).attemptedAt(source.attemptedAt ?: Instant.now())
            .completedAt(Instant.now()).errorClassification(classification).errorMessage(error)
            .metadata(source.metadata).build()
    }

    private Map callApi(String method, Map payload) {
        if (apiTransport != null) return apiTransport.call(method, payload, botToken) as Map
        byte[] body = JsonOutput.toJson(payload).getBytes(StandardCharsets.UTF_8)
        HttpRequest request = HttpRequest.newBuilder(URI.create("https://slack.com/api/${method}"))
            .timeout(Duration.ofSeconds(10))
            .header('Authorization', "Bearer ${botToken}")
            .header('Content-Type', 'application/json; charset=utf-8')
            .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build()
        HttpResponse<InputStream> response = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
            .send(request, HttpResponse.BodyHandlers.ofInputStream())
        byte[] responseBody = readBounded(response.body(), 1_048_576L)
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new SlackSurfaceException("Slack API returned HTTP ${response.statusCode()}")
        }
        def parsed = new JsonSlurper().parseText(new String(responseBody, StandardCharsets.UTF_8))
        if (!(parsed instanceof Map)) throw new SlackSurfaceException('Slack API returned a non-object response')
        parsed as Map
    }

    private static byte[] readBounded(InputStream stream, long maximum) {
        if (stream == null) return new byte[0]
        try (InputStream input = stream; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]
            long total = 0
            int count
            while ((count = input.read(buffer)) != -1) {
                total += count
                if (total > maximum) throw new SlackSurfaceException('Slack API response exceeded size limit')
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
    }

    private static PublishedMessage fromReceipt(DeliveryReceipt receipt) {
        new PublishedMessage(channelId: receipt.channelId ?: receipt.destination,
            messageTs: receipt.providerMessageId, threadTs: receipt.threadId ?: receipt.providerMessageId,
            status: receipt.status, error: receipt.errorMessage)
    }

    private String requireSecret(String envName, String label) {
        if (!envName) throw new SlackSurfaceException("${label} environment-variable name is required")
        String value = envLookup.call(envName)
        if (!value?.trim()) throw new SlackSurfaceException("${label} environment variable ${envName} is missing or blank")
        value.trim()
    }

    private void requireConnected() {
        if (!isConnected() || outbound == null) throw new IllegalStateException('Slack Socket Mode surface is not connected')
    }

    private static String stripLeadingMention(String text) {
        (text ?: '').replaceFirst(/(?i)^\s*<@[A-Z0-9]+>\s*/, '').trim()
    }

    private static String firstNonblank(String first, String fallback) { first?.trim() ? first : fallback }

    static final class SlackSurfaceException extends RuntimeException {
        SlackSurfaceException(String message, Throwable cause = null) { super(message, cause) }
    }
}
