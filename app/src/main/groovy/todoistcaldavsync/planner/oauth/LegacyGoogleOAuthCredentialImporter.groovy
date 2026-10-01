package todoistcaldavsync.planner.oauth

/** Offline-only audited import. It deliberately has no Google Calendar client dependency. */
final class LegacyGoogleOAuthCredentialImporter {
    private final String expectedAccountEmail
    private final GoogleOAuthTokenStore qaStore
    private final LegacyGoogleOAuthCredentialVerifier verifier

    LegacyGoogleOAuthCredentialImporter(String expectedAccountEmail, GoogleOAuthTokenStore qaStore,
                                        LegacyGoogleOAuthCredentialVerifier verifier) {
        if (!expectedAccountEmail || qaStore == null || verifier == null) {
            throw new IllegalArgumentException('legacy import configuration is required')
        }
        this.expectedAccountEmail = expectedAccountEmail
        this.qaStore = qaStore
        this.verifier = verifier
    }

    LegacyGoogleOAuthCredentialImporter(String expectedAccountEmail, GoogleOAuthTokenStore normalStore,
                                        GoogleOAuthTokenStore qaStore,
                                        LegacyGoogleOAuthCredentialVerifier verifier) {
        this(expectedAccountEmail, qaStore, verifier)
        if (normalStore == null) throw new IllegalArgumentException('legacy import configuration is required')
        if (normalStore.is(qaStore)) throw new IllegalArgumentException('legacy normal and QA stores must be distinct')
    }

    void importConfirmedForQa(LegacyGoogleOAuthCredential legacy, boolean operatorConfirmed) {
        if (!operatorConfirmed || legacy == null || !legacy.accessToken || !legacy.refreshToken || legacy.expiresAt == null) {
            throw new GoogleOAuthException(GoogleOAuthErrorClass.CLIENT_CONFIGURATION,
                'Legacy Google OAuth credential was not confirmed or is incomplete')
        }
        LegacyGoogleOAuthVerification evidence
        try {
            evidence = verifier.verify(legacy, expectedAccountEmail, GoogleOAuthScopes.QA_CALENDAR_MANAGEMENT)
        } catch (GoogleOAuthException e) {
            throw e
        } catch (Exception ignored) {
            throw invalidEvidence()
        }
        if (evidence == null || !evidence.accountSubject || !evidence.accountEmail) throw invalidEvidence()
        if (!expectedAccountEmail.equalsIgnoreCase(evidence.accountEmail) ||
            !expectedAccountEmail.equalsIgnoreCase(legacy.accountEmail ?: '')) {
            throw new GoogleOAuthException(GoogleOAuthErrorClass.ACCOUNT_MISMATCH,
                'Legacy Google OAuth credential belongs to a different account')
        }
        if (legacy.scopes != GoogleOAuthScopes.QA_CALENDAR_MANAGEMENT ||
            evidence.scopes != GoogleOAuthScopes.QA_CALENDAR_MANAGEMENT) {
            throw new GoogleOAuthException(GoogleOAuthErrorClass.SCOPE_MISMATCH,
                'Legacy Google OAuth credential lacks the exact QA calendar-management scope set')
        }
        qaStore.save(new GoogleOAuthTokenState(legacy.accessToken, legacy.refreshToken, legacy.expiresAt,
            GoogleOAuthScopes.QA_CALENDAR_MANAGEMENT, evidence.accountEmail, evidence.accountSubject))
    }

    void importIntoNormal(LegacyGoogleOAuthCredential ignored) {
        throw new GoogleOAuthException(GoogleOAuthErrorClass.SCOPE_MISMATCH,
            'Legacy broad Google OAuth credentials cannot be imported into the normal event-only store')
    }

    private static GoogleOAuthException invalidEvidence() {
        new GoogleOAuthException(GoogleOAuthErrorClass.TOKEN_RESPONSE_INVALID,
            'Legacy Google OAuth credential verification did not produce valid identity evidence')
    }
}
