package todoistcaldavsync.planner.oauth

/** Last-resort boundary sanitizer; OAuth services still use fixed safe error messages. */
final class GoogleOAuthRedactor {
    private GoogleOAuthRedactor() {}

    static String redact(String value) {
        if (value == null) return null
        String safe = value
        safe = safe.replaceAll(/(?i)(Authorization\s*:\s*)Bearer\s+[^\s,;]+/, '$1[REDACTED]')
        safe = safe.replaceAll(/(?i)(["']?)(client_id|client_secret|authorization_code|code|code_verifier|id_token|access_token|refresh_token)\1\s*[:=]\s*(["']?)[^\s&,;}]+\3/,
            '$1$2$1=[REDACTED]')
        safe
    }
}
