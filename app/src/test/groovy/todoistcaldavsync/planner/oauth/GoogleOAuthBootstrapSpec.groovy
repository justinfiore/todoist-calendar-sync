package todoistcaldavsync.planner.oauth

import spock.lang.Specification
import todoistcaldavsync.planner.CalendarProviderConfig

import java.net.HttpURLConnection
import java.nio.file.Path
import java.time.Duration
import java.time.Instant

class GoogleOAuthBootstrapSpec extends Specification {
    Path root = Path.of('/tmp/oauth-bootstrap-spec')
    Path clientFile = root.resolve('client.json')
    Path normalPath = root.resolve('normal')
    Path qaPath = root.resolve('qa')
    GoogleOAuthClientMaterial material = new GoogleOAuthClientMaterial('client', 'secret',
        URI.create('https://accounts.example.test/auth'), URI.create('https://oauth.example.test/token'))

    private CalendarProviderConfig.GoogleCalendarApiConfig config(int port = 8787) {
        new CalendarProviderConfig.GoogleCalendarApiConfig(clientFile, normalPath, qaPath,
            'owner@example.test', port, [])
    }

    def "normal bootstrap uses event-only scope normal store configured loopback port and terminal consent output"() {
        given:
        def normal = new InMemoryGoogleOAuthTokenStore()
        def qa = new InMemoryGoogleOAuthTokenStore()
        def calls = []
        def authorizer = { client, scopes, email, host, port, consent ->
            calls << [client: client, scopes: scopes, email: email, host: host, port: port]
            consent.accept('https://accounts.example.test/one-time-consent')
            new GoogleOAuthTokenState('access', 'refresh', Instant.parse('2030-01-01T00:00:00Z'), scopes, email, 'subject')
        } as GoogleInstalledAppAuthorizer
        def service = new GoogleOAuthBootstrapService(authorizer, { material },
            { Path path -> path == normalPath ? normal : qa })
        def terminal = new StringBuilder()

        when:
        service.bootstrap(config(9191), GoogleOAuthBootstrapMode.NORMAL, terminal)

        then:
        calls == [[client: material, scopes: GoogleOAuthScopes.EVENTS, email: 'owner@example.test',
                   host: '127.0.0.1', port: 9191]]
        terminal.toString().contains('https://accounts.example.test/one-time-consent')
        terminal.toString().contains('credential persisted')
        normal.writeCount == 1
        qa.writeCount == 0
    }

    def "QA bootstrap uses elevated exact scope and cannot overwrite normal store"() {
        given:
        def normal = new InMemoryGoogleOAuthTokenStore()
        normal.save(new GoogleOAuthTokenState('normal-access', 'normal-refresh', Instant.parse('2030-01-01T00:00:00Z'),
            GoogleOAuthScopes.EVENTS, 'owner@example.test', 'subject'))
        def qa = new InMemoryGoogleOAuthTokenStore()
        def seenScopes
        def authorizer = { client, scopes, email, host, port, consent ->
            seenScopes = scopes
            new GoogleOAuthTokenState('qa-access', 'qa-refresh', Instant.parse('2030-01-01T00:00:00Z'), scopes, email, 'subject')
        } as GoogleInstalledAppAuthorizer
        def service = new GoogleOAuthBootstrapService(authorizer, { material },
            { Path path -> path == normalPath ? normal : qa })

        when:
        service.bootstrap(config(), GoogleOAuthBootstrapMode.QA, new StringBuilder())

        then:
        seenScopes == GoogleOAuthScopes.QA_CALENDAR_MANAGEMENT
        normal.load().get().accessToken == 'normal-access'
        normal.writeCount == 1
        qa.load().get().accessToken == 'qa-access'
        qa.writeCount == 1
    }

    def "bootstrap validates refresh account and exact scope before persistence"() {
        given:
        def normal = new InMemoryGoogleOAuthTokenStore()
        def authorizer = { client, scopes, email, host, port, consent -> invalid } as GoogleInstalledAppAuthorizer
        def service = new GoogleOAuthBootstrapService(authorizer, { material }, { normal })

        when:
        service.bootstrap(config(), GoogleOAuthBootstrapMode.NORMAL, new StringBuilder())

        then:
        def error = thrown(GoogleOAuthException)
        error.classification == expected
        normal.writeCount == 0

        where:
        invalid << [
            new GoogleOAuthTokenState('a', null, Instant.parse('2030-01-01T00:00:00Z'), GoogleOAuthScopes.EVENTS, 'owner@example.test', 'subject'),
            new GoogleOAuthTokenState('a', 'r', Instant.parse('2030-01-01T00:00:00Z'), GoogleOAuthScopes.QA_CALENDAR_MANAGEMENT, 'owner@example.test', 'subject'),
            new GoogleOAuthTokenState('a', 'r', Instant.parse('2030-01-01T00:00:00Z'), GoogleOAuthScopes.EVENTS, 'wrong@example.test', 'subject')
        ]
        expected << [GoogleOAuthErrorClass.TOKEN_RESPONSE_INVALID, GoogleOAuthErrorClass.SCOPE_MISMATCH,
                     GoogleOAuthErrorClass.ACCOUNT_MISMATCH]
    }

    def "bootstrap rejects physical store aliases before authorization or store construction"() {
        given:
        Path root = java.nio.file.Files.createTempDirectory('oauth-bootstrap-alias-')
        Path physical = java.nio.file.Files.createDirectories(root.resolve('physical'))
        Path alias = java.nio.file.Files.createSymbolicLink(root.resolve('alias'), physical)
        def aliasedConfig = new CalendarProviderConfig.GoogleCalendarApiConfig(clientFile,
            physical.resolve('tokens'), alias.resolve('tokens'), 'owner@example.test', 8787, [])
        def authorizer = Mock(GoogleInstalledAppAuthorizer)
        int stores = 0
        def service = new GoogleOAuthBootstrapService(authorizer, { material }, { stores++; null })

        when:
        service.bootstrap(aliasedConfig, GoogleOAuthBootstrapMode.NORMAL, new StringBuilder())

        then:
        def error = thrown(GoogleOAuthException)
        error.classification == GoogleOAuthErrorClass.CLIENT_CONFIGURATION
        0 * authorizer._
        stores == 0
    }

    def "loopback callback receives an SSH-forwarded-style local callback and never binds wildcard"() {
        given:
        int port = freePort()
        def receiver = new LoopbackGoogleOAuthCallbackReceiver('127.0.0.1', port, Duration.ofSeconds(3))
        URI redirect = receiver.start('fixed-state')

        when:
        HttpURLConnection connection = (HttpURLConnection) URI.create(
            "${redirect}?code=callback-code&state=fixed-state").toURL().openConnection()
        int status = connection.responseCode
        String code = receiver.awaitCode()

        then:
        redirect.host == '127.0.0.1'
        redirect.port == port
        receiver.boundHost == '127.0.0.1'
        status == 200
        code == 'callback-code'

        cleanup:
        receiver?.close()
    }

    private static int freePort() {
        new ServerSocket(0, 1, InetAddress.getByName('127.0.0.1')).withCloseable { it.localPort }
    }
}
