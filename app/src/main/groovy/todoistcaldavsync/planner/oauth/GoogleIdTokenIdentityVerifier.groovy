package todoistcaldavsync.planner.oauth

import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier
import com.google.api.client.http.HttpTransport
import com.google.api.client.json.jackson2.JacksonFactory

/** Production verifier: signature, issuer, audience, time, subject, and verified email are mandatory. */
final class GoogleIdTokenIdentityVerifier implements GoogleOAuthIdentityVerifier {
    private final HttpTransport transport

    GoogleIdTokenIdentityVerifier(HttpTransport transport) {
        if (transport == null) throw new IllegalArgumentException('ID token verification transport is required')
        this.transport = transport
    }

    @Override GoogleOAuthVerifiedIdentity verify(String encoded, GoogleOAuthClientMaterial material) {
        try {
            if (!encoded || material == null || !material.clientId) throw invalid()
            GoogleIdTokenVerifier verifier = new GoogleIdTokenVerifier.Builder(transport, JacksonFactory.defaultInstance)
                .setAudience([material.clientId]).build()
            GoogleIdToken token = verifier.verify(encoded)
            String subject = token?.payload?.subject?.toString()?.trim()
            String email = token?.payload?.email?.toString()?.trim()
            if (!subject || !email || token.payload.emailVerified != Boolean.TRUE) throw invalid()
            new GoogleOAuthVerifiedIdentity(subject, email)
        } catch (GoogleOAuthException e) {
            throw e
        } catch (Exception ignored) {
            throw invalid()
        }
    }

    private static GoogleOAuthException invalid() {
        new GoogleOAuthException(GoogleOAuthErrorClass.ACCOUNT_MISMATCH,
            'Google OAuth identity evidence could not be verified')
    }
}
