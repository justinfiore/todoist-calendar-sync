package todoistcaldavsync.planner.oauth

import com.google.api.client.http.GenericUrl
import com.google.api.client.http.HttpTransport
import groovy.json.JsonSlurper

import java.nio.charset.StandardCharsets

/** Authenticates legacy access-token identity and granted scopes with Google's token-info endpoint. */
final class GoogleTokenInfoLegacyOAuthCredentialVerifier implements LegacyGoogleOAuthCredentialVerifier {
    private static final String ENDPOINT = 'https://oauth2.googleapis.com/tokeninfo'
    private final HttpTransport transport
    private final int maxResponseBytes

    GoogleTokenInfoLegacyOAuthCredentialVerifier(HttpTransport transport, int maxResponseBytes = 65_536) {
        if (transport == null || maxResponseBytes <= 0) {
            throw new IllegalArgumentException('legacy Google OAuth verifier configuration is required')
        }
        this.transport = transport
        this.maxResponseBytes = maxResponseBytes
    }

    @Override LegacyGoogleOAuthVerification verify(LegacyGoogleOAuthCredential credential,
                                                    String expectedAccountEmail, Set<String> exactQaScopes) {
        def response
        try {
            if (credential == null || !credential.accessToken || !expectedAccountEmail || !exactQaScopes) throw invalid()
            GenericUrl url = new GenericUrl(ENDPOINT)
            url.set('access_token', credential.accessToken)
            def request = transport.createRequestFactory().buildGetRequest(url)
            request.throwExceptionOnExecuteError = false
            response = request.execute()
            byte[] bytes = response.content?.readNBytes(maxResponseBytes + 1) ?: new byte[0]
            if (response.statusCode < 200 || response.statusCode >= 300 || bytes.length > maxResponseBytes) throw invalid()
            Map body = new JsonSlurper().parseText(new String(bytes, StandardCharsets.UTF_8)) as Map
            String email = body.email?.toString()?.trim()
            String subject = (body.sub ?: body.user_id)?.toString()?.trim()
            boolean emailVerified = body.email_verified == Boolean.TRUE ||
                'true'.equalsIgnoreCase(body.email_verified?.toString())
            long expiresIn
            try { expiresIn = Long.parseLong(body.expires_in?.toString()) }
            catch (Exception ignored) { throw invalid() }
            if (expiresIn <= 0) throw invalid()
            Set<String> scopes = (body.scope?.toString()?.split(/\s+/)?.findAll { it } ?: []) as LinkedHashSet<String>
            if (!subject || !email || !emailVerified || !expectedAccountEmail.equalsIgnoreCase(email)) {
                throw new GoogleOAuthException(GoogleOAuthErrorClass.ACCOUNT_MISMATCH,
                    'Legacy Google OAuth credential belongs to a different account')
            }
            if (scopes != exactQaScopes) {
                throw new GoogleOAuthException(GoogleOAuthErrorClass.SCOPE_MISMATCH,
                    'Legacy Google OAuth credential lacks the exact QA calendar-management scope set')
            }
            new LegacyGoogleOAuthVerification(subject, email, scopes)
        } catch (GoogleOAuthException e) {
            throw e
        } catch (Exception ignored) {
            throw invalid()
        } finally {
            try { response?.disconnect() } catch (Exception ignored) {}
        }
    }

    private static GoogleOAuthException invalid() {
        new GoogleOAuthException(GoogleOAuthErrorClass.TOKEN_RESPONSE_INVALID,
            'Legacy Google OAuth credential could not be authenticated')
    }
}
