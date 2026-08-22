package todoistcaldavsync.planner.oauth

/** Resolves an operator-supplied reference without exposing credential material on the command line. */
interface LegacyGoogleOAuthCredentialSource {
    LegacyGoogleOAuthCredential load(String inputReference)
}
