package todoistcaldavsync.planner.oauth

import groovy.json.JsonSlurper

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/** Loads only Google's installed/desktop client JSON from an already validated local file reference. */
final class GoogleOAuthClientMaterialLoader {
    static final long MAX_BYTES = 65_536L

    GoogleOAuthClientMaterial load(Path configuredFile) {
        try {
            if (configuredFile == null || Files.isSymbolicLink(configuredFile) ||
                !Files.isRegularFile(configuredFile, LinkOption.NOFOLLOW_LINKS) ||
                Files.size(configuredFile) > MAX_BYTES) {
                throw invalid()
            }
            def root = new JsonSlurper().parse(configuredFile.toFile())
            if (!(root instanceof Map) || !(root.installed instanceof Map) || root.size() != 1) throw invalid()
            Map installed = root.installed as Map
            String clientId = installed.client_id?.toString()?.trim()
            String clientSecret = installed.client_secret?.toString()?.trim()
            URI auth = secureUri(installed.auth_uri)
            URI token = secureUri(installed.token_uri)
            if (!clientId || !clientSecret || auth == null || token == null) throw invalid()
            new GoogleOAuthClientMaterial(clientId, clientSecret, auth, token)
        } catch (GoogleOAuthException e) {
            throw e
        } catch (Exception e) {
            throw new GoogleOAuthException(GoogleOAuthErrorClass.CLIENT_CONFIGURATION,
                'Google OAuth desktop-client file is missing, unreadable, or invalid')
        }
    }

    private static URI secureUri(def raw) {
        if (!raw) return null
        URI uri = URI.create(raw.toString())
        uri.scheme == 'https' && uri.host ? uri : null
    }

    private static GoogleOAuthException invalid() {
        new GoogleOAuthException(GoogleOAuthErrorClass.CLIENT_CONFIGURATION,
            'Google OAuth desktop-client file is missing, unreadable, or invalid')
    }
}
