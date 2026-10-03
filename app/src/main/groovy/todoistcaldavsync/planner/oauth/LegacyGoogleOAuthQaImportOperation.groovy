package todoistcaldavsync.planner.oauth

import java.nio.file.Path

/** Explicit production composition entrypoint. Invocation and verification input remain operator-controlled. */
final class LegacyGoogleOAuthQaImportOperation {
    private final LegacyGoogleOAuthCredentialSource source
    private final LegacyGoogleOAuthCredentialImporter importer

    LegacyGoogleOAuthQaImportOperation(LegacyGoogleOAuthCredentialSource source,
                                       LegacyGoogleOAuthCredentialImporter importer) {
        if (source == null || importer == null) throw new IllegalArgumentException('legacy QA import operation is incomplete')
        this.source = source
        this.importer = importer
    }

    private LegacyGoogleOAuthQaImportOperation(LegacyGoogleOAuthCredentialImporter importer) {
        this.source = null
        this.importer = importer
    }

    static LegacyGoogleOAuthQaImportOperation production(String expectedAccountEmail, Path normalStoreDirectory,
                                                         Path qaStoreDirectory,
                                                         LegacyGoogleOAuthCredentialVerifier verifier) {
        GoogleOAuthStoreIsolation.requireDistinct(normalStoreDirectory, qaStoreDirectory)
        new LegacyGoogleOAuthQaImportOperation(new LegacyGoogleOAuthCredentialImporter(expectedAccountEmail,
            new PrivateFileGoogleOAuthTokenStore(qaStoreDirectory), verifier))
    }

    static LegacyGoogleOAuthQaImportOperation production(String expectedAccountEmail, Path normalStoreDirectory,
                                                         Path qaStoreDirectory,
                                                         LegacyGoogleOAuthCredentialSource source,
                                                         LegacyGoogleOAuthCredentialVerifier verifier) {
        GoogleOAuthStoreIsolation.requireDistinct(normalStoreDirectory, qaStoreDirectory)
        new LegacyGoogleOAuthQaImportOperation(source, new LegacyGoogleOAuthCredentialImporter(expectedAccountEmail,
            new PrivateFileGoogleOAuthTokenStore(qaStoreDirectory), verifier))
    }

    void importConfirmed(LegacyGoogleOAuthCredential credential, boolean operatorConfirmed) {
        importer.importConfirmedForQa(credential, operatorConfirmed)
    }

    void importConfirmedReference(String inputReference, boolean operatorConfirmed) {
        if (!operatorConfirmed || !inputReference?.trim()) {
            throw new GoogleOAuthException(GoogleOAuthErrorClass.CLIENT_CONFIGURATION,
                'Legacy Google OAuth QA import requires explicit operator confirmation and input reference')
        }
        if (source == null) {
            throw new GoogleOAuthException(GoogleOAuthErrorClass.CLIENT_CONFIGURATION,
                'Legacy Google OAuth credential source is unavailable')
        }
        importer.importConfirmedForQa(source.load(inputReference.trim()), true)
    }
}
