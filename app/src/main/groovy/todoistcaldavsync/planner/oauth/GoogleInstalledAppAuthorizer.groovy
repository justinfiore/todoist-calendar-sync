package todoistcaldavsync.planner.oauth

import java.util.function.Consumer

interface GoogleInstalledAppAuthorizer {
    GoogleOAuthTokenState authorize(GoogleOAuthClientMaterial material, Set<String> scopes,
                                    String expectedAccountEmail, String callbackHost, int callbackPort,
                                    Consumer<String> consentUrlTerminal)
}
