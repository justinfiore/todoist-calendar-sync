package todoistcaldavsync.planner.oauth

import todoistcaldavsync.planner.CalendarProviderConfig

/** Operation-only bootstrap composition. It has no planner or Calendar API dependency. */
final class GoogleOAuthBootstrapService {
    private final GoogleInstalledAppAuthorizer authorizer
    private final Closure<GoogleOAuthClientMaterial> materialLoader
    private final Closure<GoogleOAuthTokenStore> storeFactory

    GoogleOAuthBootstrapService(GoogleInstalledAppAuthorizer authorizer,
                                Closure<GoogleOAuthClientMaterial> materialLoader,
                                Closure<GoogleOAuthTokenStore> storeFactory) {
        if (authorizer == null || materialLoader == null || storeFactory == null) {
            throw new IllegalArgumentException('Google OAuth bootstrap dependencies are required')
        }
        this.authorizer = authorizer
        this.materialLoader = materialLoader
        this.storeFactory = storeFactory
    }

    static GoogleOAuthBootstrapService production() {
        new GoogleOAuthBootstrapService(new GoogleInstalledAppOAuthAuthorizer(),
            { path -> new GoogleOAuthClientMaterialLoader().load(path) },
            { path -> new PrivateFileGoogleOAuthTokenStore(path) })
    }

    void bootstrap(CalendarProviderConfig.GoogleCalendarApiConfig config,
                   GoogleOAuthBootstrapMode mode, Appendable invokingTerminal) {
        if (config == null || mode == null || invokingTerminal == null) {
            throw new IllegalArgumentException('Google OAuth bootstrap configuration is required')
        }
        Set<String> scopes = mode == GoogleOAuthBootstrapMode.NORMAL ?
            GoogleOAuthScopes.EVENTS : GoogleOAuthScopes.QA_CALENDAR_MANAGEMENT
        def selectedPath = mode == GoogleOAuthBootstrapMode.NORMAL ? config.tokenStoreDir : config.qaTokenStoreDir
        if (selectedPath == null) {
            throw new GoogleOAuthException(GoogleOAuthErrorClass.CLIENT_CONFIGURATION,
                'Separate QA Google OAuth token store is required for QA bootstrap')
        }
        if (config.qaTokenStoreDir != null && config.qaTokenStoreDir == config.tokenStoreDir) {
            throw new GoogleOAuthException(GoogleOAuthErrorClass.CLIENT_CONFIGURATION,
                'Normal and QA Google OAuth token stores must be distinct')
        }
        if (config.qaTokenStoreDir != null) {
            GoogleOAuthStoreIsolation.requireDistinct(config.tokenStoreDir, config.qaTokenStoreDir)
        } else {
            GoogleOAuthStoreIsolation.requireIsolated(config.tokenStoreDir)
        }
        GoogleOAuthClientMaterial material = materialLoader.call(config.oauthClientSecretFile)
        GoogleOAuthTokenState credential = authorizer.authorize(material, scopes, config.accountEmail,
            '127.0.0.1', config.oauthCallbackPort,
            { String url -> invokingTerminal.append(url).append('\n') } as java.util.function.Consumer<String>)
        validateCredential(credential, scopes, config.accountEmail)
        storeFactory.call(selectedPath).save(credential)
        invokingTerminal.append('Google OAuth credential persisted; bootstrap complete.\n')
    }

    private static void validateCredential(GoogleOAuthTokenState state, Set<String> scopes, String account) {
        if (state == null || !state.accessToken || !state.refreshToken || state.expiresAt == null ||
            !state.accountSubject) {
            throw new GoogleOAuthException(GoogleOAuthErrorClass.TOKEN_RESPONSE_INVALID,
                'Google OAuth bootstrap did not return a refresh-capable credential')
        }
        if (state.scopes != scopes) {
            throw new GoogleOAuthException(GoogleOAuthErrorClass.SCOPE_MISMATCH,
                'Google OAuth bootstrap returned the wrong scope set')
        }
        if (!account.equalsIgnoreCase(state.accountEmail ?: '')) {
            throw new GoogleOAuthException(GoogleOAuthErrorClass.ACCOUNT_MISMATCH,
                'Google OAuth bootstrap returned a credential for a different account')
        }
    }
}
