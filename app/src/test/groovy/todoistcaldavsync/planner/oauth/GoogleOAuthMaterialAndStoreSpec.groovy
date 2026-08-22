package todoistcaldavsync.planner.oauth

import groovy.json.JsonOutput
import spock.lang.Specification
import spock.lang.Unroll

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant

class GoogleOAuthMaterialAndStoreSpec extends Specification {

    def "client material is loaded only from a configured desktop-client file"() {
        given:
        Path dir = Files.createTempDirectory('oauth-material-')
        Path file = dir.resolve('desktop-client.json')
        Files.writeString(file, JsonOutput.toJson([installed: [
            client_id: 'client-id.apps.example.test',
            client_secret: 'client-secret-value',
            auth_uri: 'https://accounts.google.test/o/oauth2/auth',
            token_uri: 'https://oauth2.google.test/token',
            redirect_uris: ['http://localhost']
        ]]))

        when:
        GoogleOAuthClientMaterial material = new GoogleOAuthClientMaterialLoader().load(file)

        then:
        material.clientId == 'client-id.apps.example.test'
        material.clientSecret == 'client-secret-value'
        material.authorizationEndpoint.toString() == 'https://accounts.google.test/o/oauth2/auth'
        material.tokenEndpoint.toString() == 'https://oauth2.google.test/token'
    }

    @Unroll("invalid client material fails with no secret reflection [#caseName]")
    def "invalid client material fails with no secret reflection"() {
        given:
        Path file = Files.createTempFile('oauth-material-invalid-', '.json')
        Files.writeString(file, payload)

        when:
        new GoogleOAuthClientMaterialLoader().load(file)

        then:
        def error = thrown(GoogleOAuthException)
        error.classification == GoogleOAuthErrorClass.CLIENT_CONFIGURATION
        !error.message.contains('raw-client-secret')
        !error.message.contains('raw-client-id')

        where:
        caseName    | payload
        'malformed' | '{"installed":{"client_id":"raw-client-id","client_secret":"raw-client-secret"'
        'web-type'  | JsonOutput.toJson([web: [client_id: 'raw-client-id', client_secret: 'raw-client-secret']])
        'insecure'  | JsonOutput.toJson([installed: [client_id: 'raw-client-id', client_secret: 'raw-client-secret',
            auth_uri: 'http://not-secure.test/auth', token_uri: 'https://oauth.test/token']])
    }

    def "private file token store round trips atomically with owner-only permissions"() {
        given:
        Path parent = Files.createTempDirectory('oauth-store-parent-')
        Path directory = parent.resolve('normal-tokens')
        def store = new PrivateFileGoogleOAuthTokenStore(directory)
        def state = new GoogleOAuthTokenState('access-value', 'refresh-value', Instant.parse('2030-01-01T00:00:00Z'),
            GoogleOAuthScopes.EVENTS, 'owner@example.test', 'owner-subject')

        when:
        store.save(state)

        then:
        store.load().get() == state
        Files.list(directory).withCloseable { stream ->
            stream.map { it.fileName.toString() }.toList() == ['credential.json']
        }
        if (Files.getFileStore(directory).supportsFileAttributeView('posix')) {
            assert PosixFilePermissions.toString(Files.getPosixFilePermissions(directory)) == 'rwx------'
            assert PosixFilePermissions.toString(Files.getPosixFilePermissions(directory.resolve('credential.json'))) == 'rw-------'
        }
    }

    def "in-memory token store is isolated and defensive"() {
        given:
        def normal = new InMemoryGoogleOAuthTokenStore()
        def qa = new InMemoryGoogleOAuthTokenStore()
        def state = new GoogleOAuthTokenState('access', 'refresh', Instant.parse('2030-01-01T00:00:00Z'),
            GoogleOAuthScopes.EVENTS, 'owner@example.test')

        when:
        normal.save(state)

        then:
        normal.load().get() == state
        qa.load().empty
        normal.writeCount == 1
        qa.writeCount == 0
    }

    def "normal and QA paths cannot alias through an existing intermediate symlink"() {
        given:
        Path root = Files.createTempDirectory('oauth-store-alias-')
        Path physical = Files.createDirectories(root.resolve('physical'))
        Path alias = Files.createSymbolicLink(root.resolve('alias'), physical)

        when:
        GoogleOAuthStoreIsolation.requireDistinct(root.resolve('physical/tokens'), alias.resolve('tokens'))

        then:
        def error = thrown(GoogleOAuthException)
        error.classification == GoogleOAuthErrorClass.CLIENT_CONFIGURATION
    }

    def "token store rejects any existing symlink ancestor before read or write"() {
        given:
        Path root = Files.createTempDirectory('oauth-store-symlink-')
        Path physical = Files.createDirectories(root.resolve('physical'))
        Path aliasedStore = Files.createSymbolicLink(root.resolve('alias'), physical).resolve('tokens')
        def store = new PrivateFileGoogleOAuthTokenStore(aliasedStore)

        when:
        action(store)

        then:
        def error = thrown(GoogleOAuthException)
        error.classification == GoogleOAuthErrorClass.CLIENT_CONFIGURATION
        !Files.exists(physical.resolve('tokens'))

        where:
        action << [
            { PrivateFileGoogleOAuthTokenStore it -> it.load() },
            { PrivateFileGoogleOAuthTokenStore it -> it.save(new GoogleOAuthTokenState('a', 'r',
                Instant.parse('2030-01-01T00:00:00Z'), GoogleOAuthScopes.EVENTS, 'owner@example.test', 'subject')) }
        ]
    }
}
