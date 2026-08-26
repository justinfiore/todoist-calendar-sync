package todoistcaldavsync.planner.oauth

import com.google.api.client.http.GenericUrl
import com.google.api.client.http.HttpTransport
import com.google.api.client.http.UrlEncodedContent
import groovy.json.JsonSlurper

import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import java.util.function.Supplier

/** Refreshes a single explicitly scoped credential before an API caller can obtain its access token. */
final class GoogleOAuthCredentialService {
    private final GoogleOAuthClientMaterial material
    private final GoogleOAuthTokenStore store
    private final Set<String> requiredScopes
    private final String expectedAccountEmail
    private final Supplier<Instant> clock
    private final HttpTransport transport
    private final Duration refreshWindow
    private final int maxResponseBytes

    GoogleOAuthCredentialService(GoogleOAuthClientMaterial material, GoogleOAuthTokenStore store,
                                 Collection<String> requiredScopes, String expectedAccountEmail,
                                 Supplier<Instant> clock, HttpTransport transport,
                                 Duration refreshWindow = Duration.ofMinutes(2), int maxResponseBytes = 65_536) {
        if ([material, store, clock, transport].any { it == null } || !requiredScopes || !expectedAccountEmail) {
            throw new IllegalArgumentException('complete Google OAuth lifecycle configuration is required')
        }
        this.material = material
        this.store = store
        this.requiredScopes = Collections.unmodifiableSet(new LinkedHashSet<>(requiredScopes))
        this.expectedAccountEmail = expectedAccountEmail
        this.clock = clock
        this.transport = transport
        this.refreshWindow = refreshWindow
        this.maxResponseBytes = maxResponseBytes
    }

    synchronized String accessToken() {
        GoogleOAuthTokenState current = store.load().orElseThrow {
            new GoogleOAuthException(GoogleOAuthErrorClass.CREDENTIAL_MISSING,
                'Google OAuth credential is missing; run the matching bootstrap operation')
        }
        validateState(current)
        Instant refreshAt = clock.get().plus(refreshWindow)
        if (current.expiresAt != null && current.expiresAt.isAfter(refreshAt)) return current.accessToken
        refresh(current).accessToken
    }

    private void validateState(GoogleOAuthTokenState state) {
        if (!state.refreshToken || !state.accessToken || state.expiresAt == null || !state.accountSubject) {
            throw new GoogleOAuthException(GoogleOAuthErrorClass.TOKEN_RESPONSE_INVALID,
                'Stored Google OAuth credential is incomplete')
        }
        if (!expectedAccountEmail.equalsIgnoreCase(state.accountEmail ?: '')) {
            throw new GoogleOAuthException(GoogleOAuthErrorClass.ACCOUNT_MISMATCH,
                'Stored Google OAuth credential belongs to a different account')
        }
        if (state.scopes != requiredScopes) {
            throw new GoogleOAuthException(GoogleOAuthErrorClass.SCOPE_MISMATCH,
                'Stored Google OAuth credential has the wrong scope set; run the matching bootstrap operation')
        }
    }

    private GoogleOAuthTokenState refresh(GoogleOAuthTokenState current) {
        def response
        try {
            Map form = [grant_type: 'refresh_token', refresh_token: current.refreshToken,
                        client_id: material.clientId, client_secret: material.clientSecret]
            def request = transport.createRequestFactory().buildPostRequest(
                new GenericUrl(material.tokenEndpoint), new UrlEncodedContent(form))
            request.throwExceptionOnExecuteError = false
            response = request.execute()
            byte[] bytes = response.content?.readNBytes(maxResponseBytes + 1) ?: new byte[0]
            if (bytes.length > maxResponseBytes) throw invalidResponse()
            String body = new String(bytes, StandardCharsets.UTF_8)
            Map parsed
            try { parsed = body ? new JsonSlurper().parseText(body) as Map : [:] }
            catch (Exception ignored) { throw invalidResponse() }
            if (response.statusCode < 200 || response.statusCode >= 300) {
                GoogleOAuthErrorClass kind = parsed.error?.toString() == 'invalid_grant' ?
                    GoogleOAuthErrorClass.CREDENTIAL_REVOKED : GoogleOAuthErrorClass.TOKEN_TRANSPORT
                throw new GoogleOAuthException(kind, kind == GoogleOAuthErrorClass.CREDENTIAL_REVOKED ?
                    'Google OAuth refresh credential was rejected or revoked' : 'Google OAuth token endpoint rejected refresh')
            }
            String access = parsed.access_token?.toString()
            long expiresIn
            try { expiresIn = Long.parseLong(parsed.expires_in?.toString()) }
            catch (Exception ignored) { throw invalidResponse() }
            if (!access || expiresIn <= 0 || expiresIn > 86_400L) throw invalidResponse()
            Set<String> returnedScopes
            if (!parsed.containsKey('scope')) {
                returnedScopes = requiredScopes
            } else {
                def rawScope = parsed.scope
                if (!(rawScope instanceof CharSequence) || rawScope.toString().trim().isEmpty()) {
                    throw invalidResponse()
                }
                returnedScopes = rawScope.toString().split(/\s+/).findAll { it } as LinkedHashSet<String>
            }
            if (!GoogleOAuthScopes.matchesRefreshGrant(requiredScopes, returnedScopes)) {
                throw new GoogleOAuthException(GoogleOAuthErrorClass.SCOPE_MISMATCH,
                    'Google OAuth refresh returned the wrong scope set')
            }
            def refreshed = new GoogleOAuthTokenState(access, current.refreshToken,
                clock.get().plusSeconds(expiresIn), requiredScopes, expectedAccountEmail, current.accountSubject)
            store.save(refreshed)
            refreshed
        } catch (GoogleOAuthException e) {
            throw e
        } catch (Exception e) {
            throw new GoogleOAuthException(GoogleOAuthErrorClass.TOKEN_TRANSPORT,
                'Google OAuth refresh could not be completed')
        } finally {
            try { response?.disconnect() } catch (Exception ignored) {}
        }
    }

    private static GoogleOAuthException invalidResponse() {
        new GoogleOAuthException(GoogleOAuthErrorClass.TOKEN_RESPONSE_INVALID,
            'Google OAuth token endpoint returned an invalid response')
    }
}
