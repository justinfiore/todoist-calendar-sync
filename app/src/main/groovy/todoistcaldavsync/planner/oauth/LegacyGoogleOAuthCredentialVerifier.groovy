package todoistcaldavsync.planner.oauth

/** Collaborator contract for an audited, offline verification source; it has no Calendar API dependency. */
interface LegacyGoogleOAuthCredentialVerifier {
    LegacyGoogleOAuthVerification verify(LegacyGoogleOAuthCredential credential, String expectedAccountEmail,
                                         Set<String> exactQaScopes)
}
