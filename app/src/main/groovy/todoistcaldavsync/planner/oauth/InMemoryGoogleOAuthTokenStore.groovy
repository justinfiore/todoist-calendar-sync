package todoistcaldavsync.planner.oauth

final class InMemoryGoogleOAuthTokenStore implements GoogleOAuthTokenStore {
    private GoogleOAuthTokenState state
    int writeCount

    @Override synchronized Optional<GoogleOAuthTokenState> load() { Optional.ofNullable(state) }

    @Override synchronized void save(GoogleOAuthTokenState value) {
        if (value == null) throw new IllegalArgumentException('token state is required')
        state = value
        writeCount++
    }
}
