package todoistcaldavsync.planner.oauth

import groovy.json.JsonOutput
import groovy.json.JsonSlurper

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant

/** Single-credential local store. Each lifecycle receives a distinct directory. */
final class PrivateFileGoogleOAuthTokenStore implements GoogleOAuthTokenStore {
    private static final String FILE_NAME = 'credential.json'
    private final Path directory

    PrivateFileGoogleOAuthTokenStore(Path directory) {
        if (directory == null) throw new IllegalArgumentException('token store directory is required')
        this.directory = directory
    }

    @Override Optional<GoogleOAuthTokenState> load() {
        GoogleOAuthStoreIsolation.requireIsolated(directory)
        Path file = directory.resolve(FILE_NAME)
        if (!Files.exists(file)) return Optional.empty()
        try {
            if (Files.isSymbolicLink(directory) || Files.isSymbolicLink(file) || Files.size(file) > 65_536L) {
                throw new IOException('invalid')
            }
            Map json = new JsonSlurper().parse(file.toFile()) as Map
            def state = new GoogleOAuthTokenState(
                required(json.access_token), required(json.refresh_token), Instant.parse(required(json.expires_at)),
                json.scopes instanceof Collection ? json.scopes as Collection<String> : [], required(json.account_email),
                required(json.account_subject))
            Optional.of(state)
        } catch (Exception e) {
            throw new GoogleOAuthException(GoogleOAuthErrorClass.TOKEN_RESPONSE_INVALID,
                'Stored Google OAuth credential is unreadable or invalid')
        }
    }

    @Override void save(GoogleOAuthTokenState state) {
        if (state == null || !state.accessToken || !state.refreshToken || state.expiresAt == null ||
            state.scopes.empty || !state.accountEmail || !state.accountSubject) {
            throw new GoogleOAuthException(GoogleOAuthErrorClass.TOKEN_RESPONSE_INVALID,
                'Google OAuth credential is incomplete and was not persisted')
        }
        try {
            GoogleOAuthStoreIsolation.requireIsolated(directory)
            Files.createDirectories(directory)
            if (Files.isSymbolicLink(directory) || !Files.isDirectory(directory)) throw new IOException('invalid')
            setPosix(directory, 'rwx------')
            Path temporary = Files.createTempFile(directory, '.credential-', '.tmp')
            try {
                setPosix(temporary, 'rw-------')
                String json = JsonOutput.toJson([access_token: state.accessToken, refresh_token: state.refreshToken,
                    expires_at: state.expiresAt.toString(), scopes: state.scopes as List,
                    account_email: state.accountEmail, account_subject: state.accountSubject])
                Files.writeString(temporary, json, StandardCharsets.UTF_8,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)
                Files.move(temporary, directory.resolve(FILE_NAME), StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING)
                setPosix(directory.resolve(FILE_NAME), 'rw-------')
            } finally {
                Files.deleteIfExists(temporary)
            }
        } catch (GoogleOAuthException e) {
            throw e
        } catch (Exception e) {
            throw new GoogleOAuthException(GoogleOAuthErrorClass.TOKEN_TRANSPORT,
                'Google OAuth credential could not be persisted')
        }
    }

    private static String required(def value) {
        String text = value?.toString()
        if (!text) throw new IllegalArgumentException('missing')
        text
    }

    private static void setPosix(Path path, String permissions) {
        if (Files.getFileStore(path).supportsFileAttributeView('posix')) {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(permissions))
        }
    }
}
