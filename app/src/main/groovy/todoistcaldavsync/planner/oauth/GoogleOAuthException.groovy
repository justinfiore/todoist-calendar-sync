package todoistcaldavsync.planner.oauth

final class GoogleOAuthException extends RuntimeException {
    final GoogleOAuthErrorClass classification

    GoogleOAuthException(GoogleOAuthErrorClass classification, String safeMessage, Throwable cause = null) {
        super(GoogleOAuthRedactor.redact(safeMessage), cause)
        this.classification = classification
    }
}
