package todoistcaldavsync.planner.oauth

import com.google.api.client.http.GenericUrl
import com.google.api.client.http.HttpTransport
import com.google.api.client.http.UrlEncodedContent
import com.google.api.client.http.javanet.NetHttpTransport
import groovy.json.JsonSlurper

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.function.Consumer
import java.util.function.Supplier

/** Interactive installed-app authorization; constructed only by an explicit bootstrap operation. */
final class GoogleInstalledAppOAuthAuthorizer implements GoogleInstalledAppAuthorizer {
    private final HttpTransport transport
    private final Supplier<Instant> clock
    private final Supplier<String> stateFactory
    private final Supplier<String> pkceVerifierFactory
    private final GoogleOAuthIdentityVerifier identityVerifier
    private final Closure<LoopbackGoogleOAuthCallbackReceiver> receiverFactory

    GoogleInstalledAppOAuthAuthorizer(HttpTransport transport = new NetHttpTransport(),
                                      Supplier<Instant> clock = { Instant.now() },
                                      Supplier<String> stateFactory = { UUID.randomUUID().toString() },
                                      Supplier<String> pkceVerifierFactory = {
                                          Base64.urlEncoder.withoutPadding().encodeToString(UUID.randomUUID().toString().bytes)
                                      },
                                      GoogleOAuthIdentityVerifier identityVerifier = null,
                                      Closure<LoopbackGoogleOAuthCallbackReceiver> receiverFactory =
                                          { host, port -> new LoopbackGoogleOAuthCallbackReceiver(host, port) }) {
        this.transport = transport
        this.clock = clock
        this.stateFactory = stateFactory
        this.pkceVerifierFactory = pkceVerifierFactory
        this.identityVerifier = identityVerifier ?: new GoogleIdTokenIdentityVerifier(transport)
        this.receiverFactory = receiverFactory
    }

    @Override GoogleOAuthTokenState authorize(GoogleOAuthClientMaterial material, Set<String> scopes,
                                               String expectedAccountEmail, String callbackHost, int callbackPort,
                                               Consumer<String> consentUrlTerminal) {
        String state = stateFactory.get()
        String codeVerifier = pkceVerifierFactory.get()
        if (!validPkceVerifier(codeVerifier)) {
            throw new GoogleOAuthException(GoogleOAuthErrorClass.CLIENT_CONFIGURATION,
                'Google OAuth PKCE verifier could not be created')
        }
        Set<String> requestedScopes = new LinkedHashSet<>(scopes)
        requestedScopes.addAll(GoogleOAuthScopes.IDENTITY)
        def receiver = receiverFactory.call(callbackHost, callbackPort)
        try {
            URI redirect = receiver.start(state)
            GenericUrl consent = new GenericUrl(material.authorizationEndpoint)
            consent.set('client_id', material.clientId)
            consent.set('redirect_uri', redirect.toString())
            consent.set('response_type', 'code')
            consent.set('scope', requestedScopes.join(' '))
            consent.set('access_type', 'offline')
            consent.set('prompt', 'consent')
            consent.set('login_hint', expectedAccountEmail)
            consent.set('state', state)
            consent.set('code_challenge', pkceChallenge(codeVerifier))
            consent.set('code_challenge_method', 'S256')
            consentUrlTerminal.accept(consent.toString())
            String code = receiver.awaitCode()
            exchange(material, scopes, requestedScopes, expectedAccountEmail, redirect, code, codeVerifier)
        } finally {
            receiver.close()
        }
    }

    private GoogleOAuthTokenState exchange(GoogleOAuthClientMaterial material, Set<String> scopes,
                                            Set<String> requestedScopes, String account, URI redirect,
                                            String code, String codeVerifier) {
        def response
        try {
            def request = transport.createRequestFactory().buildPostRequest(new GenericUrl(material.tokenEndpoint),
                new UrlEncodedContent([grant_type: 'authorization_code', code: code, client_id: material.clientId,
                    client_secret: material.clientSecret, redirect_uri: redirect.toString(),
                    code_verifier: codeVerifier]))
            request.throwExceptionOnExecuteError = false
            response = request.execute()
            byte[] bytes = response.content?.readNBytes(65_537) ?: new byte[0]
            if (bytes.length > 65_536) throw invalid()
            Map parsed
            try { parsed = new JsonSlurper().parseText(new String(bytes, StandardCharsets.UTF_8)) as Map }
            catch (Exception ignored) { throw invalid() }
            if (response.statusCode < 200 || response.statusCode >= 300) {
                throw new GoogleOAuthException(GoogleOAuthErrorClass.TOKEN_TRANSPORT,
                    'Google OAuth authorization-code exchange was rejected')
            }
            String access = parsed.access_token?.toString()
            String refresh = parsed.refresh_token?.toString()
            long seconds
            try { seconds = Long.parseLong(parsed.expires_in?.toString()) }
            catch (Exception ignored) { throw invalid() }
            Set<String> returned = parsed.scope ?
                (parsed.scope.toString().split(/\s+/).findAll { it } as LinkedHashSet<String>) : [] as Set<String>
            String idToken = parsed.id_token?.toString()
            if (!access || !refresh || !idToken || seconds <= 0 || seconds > 86_400 || returned != requestedScopes) throw invalid()
            GoogleOAuthVerifiedIdentity identity = identityVerifier.verify(idToken, material)
            if (!identity?.subject?.trim() || !identity?.email?.trim() ||
                !account.equalsIgnoreCase(identity.email)) {
                throw new GoogleOAuthException(GoogleOAuthErrorClass.ACCOUNT_MISMATCH,
                    'Google OAuth authorization returned a different or unverified account')
            }
            new GoogleOAuthTokenState(access, refresh, clock.get().plusSeconds(seconds), scopes,
                identity.email, identity.subject)
        } catch (GoogleOAuthException e) {
            throw e
        } catch (Exception e) {
            throw new GoogleOAuthException(GoogleOAuthErrorClass.TOKEN_TRANSPORT,
                'Google OAuth authorization-code exchange could not be completed')
        } finally {
            try { response?.disconnect() } catch (Exception ignored) {}
        }
    }

    private static GoogleOAuthException invalid() {
        new GoogleOAuthException(GoogleOAuthErrorClass.TOKEN_RESPONSE_INVALID,
            'Google OAuth authorization-code exchange returned an invalid response')
    }

    private static boolean validPkceVerifier(String value) {
        value != null && value.length() >= 43 && value.length() <= 128 && value ==~ /[A-Za-z0-9._~-]+/
    }

    private static String pkceChallenge(String verifier) {
        Base64.urlEncoder.withoutPadding().encodeToString(
            MessageDigest.getInstance('SHA-256').digest(verifier.getBytes(StandardCharsets.US_ASCII)))
    }
}
