package todoistcaldavsync.planner.oauth

import groovy.transform.EqualsAndHashCode

import java.net.URI

@EqualsAndHashCode
final class GoogleOAuthClientMaterial {
    final String clientId
    final String clientSecret
    final URI authorizationEndpoint
    final URI tokenEndpoint

    GoogleOAuthClientMaterial(String clientId, String clientSecret, URI authorizationEndpoint, URI tokenEndpoint) {
        this.clientId = clientId
        this.clientSecret = clientSecret
        this.authorizationEndpoint = authorizationEndpoint
        this.tokenEndpoint = tokenEndpoint
    }
}
