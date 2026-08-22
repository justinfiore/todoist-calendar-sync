package todoistcaldavsync.planner.oauth

import groovy.transform.EqualsAndHashCode

/** Identity evidence returned only after cryptographic ID-token verification. */
@EqualsAndHashCode
final class GoogleOAuthVerifiedIdentity {
    final String subject
    final String email

    GoogleOAuthVerifiedIdentity(String subject, String email) {
        this.subject = subject
        this.email = email
    }
}
