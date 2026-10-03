package todoistcaldavsync.planner.oauth

interface GoogleOAuthTokenStore {
    Optional<GoogleOAuthTokenState> load()
    void save(GoogleOAuthTokenState state)
}
