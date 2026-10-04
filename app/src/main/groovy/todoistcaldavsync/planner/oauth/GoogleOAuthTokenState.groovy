package todoistcaldavsync.planner.oauth

import groovy.transform.EqualsAndHashCode

import java.time.Instant

@EqualsAndHashCode
final class GoogleOAuthTokenState {
    final String accessToken
    final String refreshToken
    final Instant expiresAt
    final Set<String> scopes
    final String accountEmail
    final String accountSubject

    GoogleOAuthTokenState(String accessToken, String refreshToken, Instant expiresAt,
                          Collection<String> scopes, String accountEmail, String accountSubject = null) {
        this.accessToken = accessToken
        this.refreshToken = refreshToken
        this.expiresAt = expiresAt
        this.scopes = Collections.unmodifiableSet(new LinkedHashSet<>(scopes ?: []))
        this.accountEmail = accountEmail
        this.accountSubject = accountSubject
    }
}
