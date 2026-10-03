package todoistcaldavsync.planner.oauth

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/** Resolves existing ancestors without following configured symlink components. */
final class GoogleOAuthStoreIsolation {
    private GoogleOAuthStoreIsolation() {}

    static void requireDistinct(Path normal, Path qa) {
        Path normalPhysical = isolatedPhysicalPath(normal)
        Path qaPhysical = isolatedPhysicalPath(qa)
        if (normalPhysical == qaPhysical || normalPhysical.startsWith(qaPhysical) || qaPhysical.startsWith(normalPhysical)) {
            throw invalid()
        }
    }

    static Path requireIsolated(Path path) {
        isolatedPhysicalPath(path)
        path
    }

    private static Path isolatedPhysicalPath(Path raw) {
        try {
            if (raw == null) throw invalid()
            Path absolute = raw.toAbsolutePath().normalize()
            Path current = absolute.root
            Path existing = current
            int existingCount = 0
            for (Path component : absolute) {
                current = current.resolve(component)
                if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) break
                if (Files.isSymbolicLink(current)) throw invalid()
                existing = current
                existingCount++
            }
            Path canonical = existing.toRealPath(LinkOption.NOFOLLOW_LINKS)
            existingCount == absolute.nameCount ? canonical :
                canonical.resolve(absolute.subpath(existingCount, absolute.nameCount)).normalize()
        } catch (GoogleOAuthException e) {
            throw e
        } catch (Exception ignored) {
            throw invalid()
        }
    }

    private static GoogleOAuthException invalid() {
        new GoogleOAuthException(GoogleOAuthErrorClass.CLIENT_CONFIGURATION,
            'Normal and QA Google OAuth token stores must be distinct non-symlink directories')
    }
}
