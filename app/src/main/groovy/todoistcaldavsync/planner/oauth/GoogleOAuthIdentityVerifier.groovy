package todoistcaldavsync.planner.oauth

interface GoogleOAuthIdentityVerifier {
    GoogleOAuthVerifiedIdentity verify(String idToken, GoogleOAuthClientMaterial material)
}
