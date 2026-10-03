package todoistcaldavsync.planner.oauth

import groovy.transform.EqualsAndHashCode

/** Evidence produced by the independent legacy-credential verification collaborator. */
@EqualsAndHashCode
final class LegacyGoogleOAuthVerification {
    final String accountSubject
    final String accountEmail
    final Set<String> scopes

    LegacyGoogleOAuthVerification(String accountSubject, String accountEmail, Collection<String> scopes) {
        this.accountSubject = accountSubject
        this.accountEmail = accountEmail
        this.scopes = Collections.unmodifiableSet(new LinkedHashSet<>(scopes ?: []))
    }
}
