package todoistcaldavsync.planner.adapters

import spock.lang.Specification
import todoistcaldavsync.planner.domain.Message

import java.net.URI
import java.net.http.HttpTimeoutException
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

class SlackMessagingGatewaySpec extends Specification {

    Instant now = Instant.parse('2026-08-07T12:00:00Z')

    private Message sample(String body = 'Hello <world> & co') {
        Message.builder()
            .kind('daily_summary')
            .subject('Daily plan')
            .body(body)
            .destination('#planner')
            .planId('p1')
            .planVersion(1)
            .planHash('abc123def456')
            .proposalId('prop-p1-v1-abc123def456')
            .idempotencyKey('msg-daily-001')
            .createdAt(now)
            .build()
    }

    def "webhook send succeeds with injectable transport and escapes mrkdwn"() {
        given:
        def captured = new AtomicReference<SlackMessagingGateway.HttpCall>()
        def gw = new SlackMessagingGateway(
            mode: 'webhook',
            webhookUrlEnv: 'SLACK_WEBHOOK_URL',
            destination: '#planner',
            envResolver: { String n -> n == 'SLACK_WEBHOOK_URL' ? 'https://hooks.slack.com/services/T/B/xxx' : null },
            transport: { SlackMessagingGateway.HttpCall c ->
                captured.set(c)
                new SlackMessagingGateway.HttpResult(200, 'ok')
            },
            clock: { now }
        )

        when:
        def receipt = gw.send(sample())

        then:
        receipt.status == 'DELIVERED'
        captured.get().uri.host == 'hooks.slack.com'
        captured.get().body.contains('&lt;world&gt;')
        captured.get().body.contains('&amp;')
        !captured.get().body.toLowerCase().contains('xoxb')
        !receipt.errorMessage
    }

    def "chat_api mode uses bot token env and never logs raw token"() {
        given:
        def captured = new AtomicReference<SlackMessagingGateway.HttpCall>()
        def gw = new SlackMessagingGateway(
            mode: 'chat_api',
            botTokenEnv: 'SLACK_BOT_TOKEN',
            destination: 'C0123',
            envResolver: { String n -> n == 'SLACK_BOT_TOKEN' ? 'xoxb-secret-token-value' : null },
            transport: { SlackMessagingGateway.HttpCall c ->
                captured.set(c)
                new SlackMessagingGateway.HttpResult(200,
                    '{"ok":true,"ts":"123.456","channel":"C0123"}')
            },
            clock: { now }
        )

        when:
        def receipt = gw.send(sample())

        then:
        receipt.status == 'DELIVERED'
        receipt.providerMessageId == '123.456'
        receipt.channelId == 'C0123'
        captured.get().uri.host == 'slack.com'
        captured.get().uri.path.startsWith('/api/')
        captured.get().headers.Authorization.contains('Bearer')
        // fingerprint redacted
        captured.get().credentialFingerprint != 'xoxb-secret-token-value'
        !receipt.toMap().toString().contains('xoxb-secret-token-value')
    }

    def "rejects non-HTTPS and non-allowlisted hosts"() {
        given:
        def gw = new SlackMessagingGateway(
            mode: 'webhook',
            webhookUrlOverride: url,
            destination: '#x',
            transport: { c -> new SlackMessagingGateway.HttpResult(200, 'ok') },
            clock: { now }
        )

        when:
        def r = gw.send(sample())

        then:
        r.status == 'FAILED'
        r.errorClassification == 'ENDPOINT'

        where:
        url << [
            'http://hooks.slack.com/services/T/B/x',
            'https://evil.example.com/hooks',
            'https://hooks.slack.com.evil.com/x'
        ]
    }

    def "truncation inserts omitted-count marker"() {
        when:
        def longText = 'x' * 5000
        def out = SlackMessagingGateway.truncateWithOmitted(longText, 100)

        then:
        out.length() <= 100
        out.contains('omitted')
    }

    def "escape mrkdwn is deterministic"() {
        expect:
        SlackMessagingGateway.escapeMrkdwn('a <b> & c > d') == 'a &lt;b&gt; &amp; c &gt; d'
    }

    def "429 captures retry-after without sleeping"() {
        given:
        def gw = new SlackMessagingGateway(
            mode: 'webhook',
            webhookUrlOverride: 'https://hooks.slack.com/services/T/B/x',
            transport: { c ->
                new SlackMessagingGateway.HttpResult(429, 'rate limited',
                    ['Retry-After': ['12']])
            },
            clock: { now }
        )

        when:
        def r = gw.send(sample())

        then:
        r.status == 'FAILED'
        r.errorClassification == 'RATE_LIMIT'
        r.metadata.retryAfterSeconds == 12L
        r.errorMessage.contains('retry-after=12')
    }

    def "4xx and 5xx classified"() {
        given:
        def gw = new SlackMessagingGateway(
            mode: 'webhook',
            webhookUrlOverride: 'https://hooks.slack.com/services/T/B/x',
            transport: { c -> new SlackMessagingGateway.HttpResult(status, 'err') },
            clock: { now }
        )

        expect:
        gw.send(sample()).errorClassification == cls

        where:
        status | cls
        400    | 'HTTP_4XX'
        404    | 'HTTP_4XX'
        500    | 'HTTP_5XX'
        503    | 'HTTP_5XX'
    }

    def "timeout classified as TIMEOUT"() {
        given:
        def gw = new SlackMessagingGateway(
            mode: 'webhook',
            webhookUrlOverride: 'https://hooks.slack.com/services/T/B/x',
            transport: { c -> throw new HttpTimeoutException('request timed out') },
            clock: { now }
        )

        when:
        def r = gw.send(sample())

        then:
        r.status == 'FAILED'
        r.errorClassification == 'TIMEOUT'
    }

    def "malformed JSON response fails without delivered"() {
        given:
        def gw = new SlackMessagingGateway(
            mode: 'chat_api',
            botTokenOverride: 'xoxb-test',
            destination: 'C1',
            transport: { c -> new SlackMessagingGateway.HttpResult(200, 'not-json') },
            clock: { now }
        )

        when:
        def r = gw.send(sample())

        then:
        r.status == 'FAILED'
        r.errorClassification == 'MALFORMED_JSON'
    }

    def "chat_api ok:false is PROVIDER failure"() {
        given:
        def gw = new SlackMessagingGateway(
            mode: 'chat_api',
            botTokenOverride: 'xoxb-test',
            destination: 'C1',
            transport: { c -> new SlackMessagingGateway.HttpResult(200, '{"ok":false,"error":"channel_not_found"}') },
            clock: { now }
        )

        when:
        def r = gw.send(sample())

        then:
        r.status == 'FAILED'
        r.errorClassification == 'PROVIDER'
        r.errorMessage.contains('channel_not_found')
    }

    def "missing env secret fails CONFIG without leaking"() {
        given:
        def gw = new SlackMessagingGateway(
            mode: 'webhook',
            webhookUrlEnv: 'MISSING_WEBHOOK',
            envResolver: { String n -> null },
            transport: { c -> new SlackMessagingGateway.HttpResult(200, 'ok') },
            clock: { now }
        )

        when:
        def r = gw.send(sample())

        then:
        r.status == 'FAILED'
        r.errorClassification == 'CONFIG'
        r.errorMessage.contains('MISSING_WEBHOOK')
        !r.errorMessage.toLowerCase().contains('xox')
    }

    def "scrub redacts tokens and webhook paths"() {
        expect:
        SlackMessagingGateway.scrub('Bearer xoxb-secret-123 and hooks.slack.com/services/T/B/XYZ')
            .contains('***')
        !SlackMessagingGateway.scrub('Bearer xoxb-secret-123').contains('xoxb-secret-123')
    }

    def "bounded body oversize is CONTENT"() {
        when:
        SlackMessagingGateway.readBounded(
            new ByteArrayInputStream(('y' * 100).getBytes('UTF-8')), 50)

        then:
        def e = thrown(SlackMessagingGateway.MessagingGatewayException)
        e.classification == 'CONTENT'
    }

    def "validateEndpoint webhook allows only hooks.slack.com https default port"() {
        given:
        def gw = new SlackMessagingGateway(
            mode: 'webhook',
            webhookUrlOverride: 'https://hooks.slack.com/services/T/B/x',
            transport: { c -> new SlackMessagingGateway.HttpResult(200, 'ok') }
        )

        when:
        gw.validateEndpoint(URI.create('https://hooks.slack.com/services/T/B/x'))

        then:
        noExceptionThrown()

        when:
        gw.validateEndpoint(URI.create(url))

        then:
        def e = thrown(SlackMessagingGateway.MessagingGatewayException)
        e.classification == 'ENDPOINT'

        where:
        url << [
            'https://example.com',
            'https://api.slack.com/api/chat.postMessage',
            'https://www.slack.com/services/x',
            'https://hooks.slack.com.evil.com/x',
            'https://evil-hooks.slack.com/x',
            'https://user:pass@hooks.slack.com/services/T/B/x',
            'https://hooks.slack.com:8443/services/T/B/x',
            'http://hooks.slack.com/services/T/B/x'
        ]
    }

    def "validateEndpoint chat_api requires slack.com host and /api/ path"() {
        given:
        def gw = new SlackMessagingGateway(
            mode: 'chat_api',
            botTokenOverride: 'xoxb-test',
            destination: 'C1',
            transport: { c -> new SlackMessagingGateway.HttpResult(200, '{"ok":true,"ts":"1"}') }
        )

        when:
        gw.validateEndpoint(URI.create('https://slack.com/api/chat.postMessage'))

        then:
        noExceptionThrown()

        when:
        gw.validateEndpoint(URI.create(url))

        then:
        def e = thrown(SlackMessagingGateway.MessagingGatewayException)
        e.classification == 'ENDPOINT'

        where:
        url << [
            'https://api.slack.com/api/chat.postMessage',
            'https://www.slack.com/api/chat.postMessage',
            'https://slack.com/chat.postMessage',
            'https://slack.com/',
            'https://hooks.slack.com/services/T/B/x',
            'https://slack.com.evil.com/api/x',
            'https://user@slack.com/api/chat.postMessage',
            'https://slack.com:8443/api/chat.postMessage'
        ]
    }

    def "chat_api default endpoint uses slack.com not api.slack.com"() {
        given:
        def captured = new AtomicReference<SlackMessagingGateway.HttpCall>()
        def gw = new SlackMessagingGateway(
            mode: 'chat_api',
            botTokenOverride: 'xoxb-test',
            destination: 'C1',
            transport: { SlackMessagingGateway.HttpCall c ->
                captured.set(c)
                new SlackMessagingGateway.HttpResult(200, '{"ok":true,"ts":"1.2","channel":"C1"}')
            },
            clock: { now }
        )

        when:
        def r = gw.send(sample())

        then:
        r.status == 'DELIVERED'
        captured.get().uri.host == 'slack.com'
        captured.get().uri.path.startsWith('/api/')
    }

    def "webhook strict success only plain ok; empty whitespace html json fail"() {
        given:
        def gw = new SlackMessagingGateway(
            mode: 'webhook',
            webhookUrlOverride: 'https://hooks.slack.com/services/T/B/x',
            transport: { c -> new SlackMessagingGateway.HttpResult(200, body) },
            clock: { now }
        )

        when:
        def r = gw.send(sample())

        then:
        r.status == expectedStatus
        if (expectedStatus == 'FAILED') {
            assert r.errorClassification == 'WEBHOOK_BODY'
            assert r.metadata.responseClass != null
        }

        where:
        body                         | expectedStatus
        'ok'                         | 'DELIVERED'
        'OK'                         | 'DELIVERED'
        ' ok '                       | 'DELIVERED'
        ''                           | 'FAILED'
        '   '                        | 'FAILED'
        '<html>proxy</html>'         | 'FAILED'
        '{}'                         | 'FAILED'
        '{"ok":true}'                | 'FAILED'
        '{"ok":false}'               | 'FAILED'
        'not-ok'                     | 'FAILED'
    }

    def "chat_api requires JSON ok true; false empty malformed fail"() {
        given:
        def gw = new SlackMessagingGateway(
            mode: 'chat_api',
            botTokenOverride: 'xoxb-test',
            destination: 'C1',
            transport: { c -> new SlackMessagingGateway.HttpResult(200, body) },
            clock: { now }
        )

        when:
        def r = gw.send(sample())

        then:
        r.status == expectedStatus
        r.errorClassification == cls || expectedStatus == 'DELIVERED'

        where:
        body                              | expectedStatus | cls
        '{"ok":true,"ts":"1"}'            | 'DELIVERED'    | null
        '{"ok":false,"error":"channel_not_found"}' | 'FAILED' | 'PROVIDER'
        ''                                | 'FAILED'       | 'MALFORMED_JSON'
        '   '                             | 'FAILED'       | 'MALFORMED_JSON'
        'not-json'                        | 'FAILED'       | 'MALFORMED_JSON'
        '[]'                              | 'FAILED'       | 'SCHEMA'
        '{}'                              | 'FAILED'       | 'SCHEMA'
        '{"ok":"yes"}'                    | 'FAILED'       | 'SCHEMA'
        '{"ok":"true"}'                   | 'FAILED'       | 'SCHEMA'
        '{"ok":"TRUE"}'                   | 'FAILED'       | 'SCHEMA'
        '{"ok":1}'                        | 'FAILED'       | 'SCHEMA'
        '{"ok":1.0}'                      | 'FAILED'       | 'SCHEMA'
    }

    def "chat_api rejects string true number 1 and truthy ok values as SCHEMA not DELIVERED"() {
        given:
        def gw = new SlackMessagingGateway(
            mode: 'chat_api',
            botTokenOverride: 'xoxb-test',
            destination: 'C1',
            transport: { c -> new SlackMessagingGateway.HttpResult(200, body) },
            clock: { now }
        )

        when:
        def r = gw.send(sample())

        then:
        r.status == 'FAILED'
        r.errorClassification == 'SCHEMA'
        r.metadata.responseClass == 'ok_invalid'

        where:
        body << [
            '{"ok":"true","ts":"1"}',
            '{"ok":"True","ts":"1"}',
            '{"ok":1,"ts":"1"}',
            '{"ok":"1","ts":"1"}',
            '{"ok":"yes","ts":"1"}',
            '{"ok":"ok","ts":"1"}',
            '{"ok":[true],"ts":"1"}',
            '{"ok":{"v":true},"ts":"1"}'
        ]
    }

    def "context metadata escapes hostile plan proposal kind destination thread ids"() {
        given:
        def captured = new AtomicReference<SlackMessagingGateway.HttpCall>()
        def gw = new SlackMessagingGateway(
            mode: 'webhook',
            webhookUrlOverride: 'https://hooks.slack.com/services/T/B/x',
            destination: '#planner',
            transport: { c ->
                captured.set(c)
                new SlackMessagingGateway.HttpResult(200, 'ok')
            },
            clock: { now }
        )
        def hostile = Message.builder()
            .kind('daily_summary<script>')
            .subject('subj')
            .body('body')
            .destination('<!channel>')
            .planId('<@U123>')
            .planVersion(1)
            .planHash('abc&def*ghi`jkl')
            .proposalId('prop-<@U>&x')
            .idempotencyKey('k1')
            .createdAt(now)
            .metadata([threadTs: '123.456\n<!here>'])
            .build()

        when:
        def r = gw.send(hostile)
        def payload = captured.get().body

        then:
        r.status == 'DELIVERED'
        // Raw injection tokens must not appear unescaped
        !payload.contains('<@U123>')
        !payload.contains('<!channel>')
        !payload.contains('<!here>')
        payload.contains('&lt;@U123&gt;') || payload.contains('&lt;@U')
        payload.contains('&lt;!channel&gt;') || payload.contains('&amp;')
        // amp and angle brackets escaped in meta
        payload.contains('&amp;') || payload.contains('&lt;')
        // Newlines collapsed out of meta values
        !payload.contains('123.456\n')
    }

    def "escapeAndBoundMeta is deterministic and bounds length"() {
        expect:
        SlackMessagingGateway.escapeAndBoundMeta('<@U> & * `') ==
            SlackMessagingGateway.escapeAndBoundMeta('<@U> & * `')
        SlackMessagingGateway.escapeAndBoundMeta('a <b> & c') == 'a &lt;b&gt; &amp; c'
        SlackMessagingGateway.escapeAndBoundMeta('x' * 500).length() <= 120
        SlackMessagingGateway.escapeAndBoundMeta('line1\nline2').contains('line1 line2')
    }

    def "buildHttpClient follows Redirect.NEVER and finite connect timeout"() {
        given:
        def timeout = Duration.ofSeconds(7)

        when:
        def client = SlackMessagingGateway.buildHttpClient(timeout)

        then:
        client != null
        client.followRedirects() == java.net.http.HttpClient.Redirect.NEVER
        client.connectTimeout().isPresent()
        client.connectTimeout().get() == timeout
        !client.connectTimeout().get().isZero()
        !client.connectTimeout().get().isNegative()

        when: 'default timeout used when null arg'
        def defClient = SlackMessagingGateway.buildHttpClient(null)
        then:
        defClient.followRedirects() == java.net.http.HttpClient.Redirect.NEVER
        defClient.connectTimeout().get() == SlackMessagingGateway.DEFAULT_TIMEOUT

        when: 'non-positive timeout rejected'
        SlackMessagingGateway.buildHttpClient(Duration.ZERO)
        then:
        thrown(IllegalArgumentException)
    }
}
