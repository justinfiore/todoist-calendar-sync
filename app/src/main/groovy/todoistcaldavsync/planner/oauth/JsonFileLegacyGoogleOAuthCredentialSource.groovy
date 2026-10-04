package todoistcaldavsync.planner.oauth

import groovy.json.JsonSlurper

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.time.Instant

/** Bounded explicit-file adapter for operator-controlled legacy input. */
final class JsonFileLegacyGoogleOAuthCredentialSource implements LegacyGoogleOAuthCredentialSource {
    private final int maxBytes

    JsonFileLegacyGoogleOAuthCredentialSource(int maxBytes = 65_536) {
        if (maxBytes <= 0) throw new IllegalArgumentException('legacy credential input limit must be positive')
        this.maxBytes = maxBytes
    }

    @Override LegacyGoogleOAuthCredential load(String inputReference) {
        if (!inputReference?.trim()) throw invalid()
        try {
            Path path = Path.of(inputReference.trim())
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > maxBytes) throw invalid()
            byte[] content
            Files.newInputStream(path).withCloseable { stream ->
                content = stream.readNBytes(maxBytes + 1)
            }
            if (content.length > maxBytes) throw invalid()
            Map parsed = new JsonSlurper().parse(content) as Map
            String accessToken = value(parsed, 'access_token', 'accessToken')
            String refreshToken = value(parsed, 'refresh_token', 'refreshToken')
            String expiresAt = value(parsed, 'expires_at', 'expiresAt')
            String accountEmail = value(parsed, 'account_email', 'accountEmail')
            def rawScopes = parsed.containsKey('scopes') ? parsed.scopes : parsed.scope
            Collection<String> scopes = rawScopes instanceof Collection ?
                (rawScopes as Collection).collect { it?.toString() } : rawScopes?.toString()?.split(/\s+/)?.findAll { it }
            if (!accessToken || !refreshToken || !expiresAt || !accountEmail || !scopes) throw invalid()
            new LegacyGoogleOAuthCredential(accessToken, refreshToken, Instant.parse(expiresAt), scopes, accountEmail)
        } catch (GoogleOAuthException e) {
            throw e
        } catch (Exception ignored) {
            throw invalid()
        }
    }

    private static String value(Map parsed, String snake, String camel) {
        (parsed[snake] ?: parsed[camel])?.toString()?.trim()
    }

    private static GoogleOAuthException invalid() {
        new GoogleOAuthException(GoogleOAuthErrorClass.CLIENT_CONFIGURATION,
            'Legacy Google OAuth input reference could not be loaded as a bounded credential document')
    }
}
