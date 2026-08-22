package todoistcaldavsync.planner.oauth

import java.time.Instant

/** Audited legacy input after its datastore has been decoded by an explicit operator-side adapter. */
final class LegacyGoogleOAuthCredential {
    final String accessToken
    final String refreshToken
    final Instant expiresAt
    final Set<String> scopes
    final String accountEmail

    LegacyGoogleOAuthCredential(String accessToken, String refreshToken, Instant expiresAt,
                                Collection<String> scopes, String accountEmail) {
        this.accessToken = accessToken
        this.refreshToken = refreshToken
        this.expiresAt = expiresAt
        this.scopes = Collections.unmodifiableSet(new LinkedHashSet<>(scopes ?: []))
        this.accountEmail = accountEmail
    }
}
