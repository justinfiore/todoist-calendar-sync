package todoistcaldavsync.planner.state

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Resolves index {@code file} entries strictly inside a store directory.
 * Rejects absolute paths, empty names, {@code ..} traversal, and symlink escape.
 */
final class StorePaths {
    private StorePaths() {}

    /**
     * Resolve {@code relativeFile} under {@code storeDirectory}.
     * @throws PlanStoreException structured corruption when the candidate escapes the store
     */
    static Path resolveContained(Path storeDirectory, String relativeFile, String context = 'index') {
        if (storeDirectory == null) {
            throw new IllegalArgumentException('storeDirectory is required')
        }
        if (relativeFile == null || relativeFile.trim().isEmpty()) {
            throw new PlanStoreException(
                'Store index file entry is empty', storeDirectory.toString(), context)
        }
        String raw = relativeFile.trim()
        if (raw.indexOf(0) >= 0) {
            throw new PlanStoreException(
                'Store index file entry contains NUL', storeDirectory.toString(), context)
        }
        Path asPath
        try {
            asPath = Paths.get(raw)
        } catch (Exception e) {
            throw new PlanStoreException(
                "Store index file entry is not a valid path: ${raw}",
                storeDirectory.toString(), context, e)
        }
        if (asPath.isAbsolute()) {
            throw new PlanStoreException(
                "Store index file entry must be relative, got absolute: ${raw}",
                storeDirectory.toString(), context)
        }
        for (Path part : asPath) {
            String name = part.toString()
            if (name == '..' || name == '.' ) {
                if (name == '..') {
                    throw new PlanStoreException(
                        "Store index file entry must not contain '..': ${raw}",
                        storeDirectory.toString(), context)
                }
            }
        }
        // Reject any remaining parent-dir segments after normalize of the relative form
        Path normalizedRelative = asPath.normalize()
        if (normalizedRelative.toString().startsWith('..') ||
            normalizedRelative.iterator().any { it.toString() == '..' }) {
            throw new PlanStoreException(
                "Store index file entry escapes store after normalize: ${raw}",
                storeDirectory.toString(), context)
        }

        Path base
        try {
            base = storeDirectory.toAbsolutePath().normalize()
        } catch (Exception e) {
            throw new PlanStoreException(
                "Unable to normalize store directory: ${storeDirectory}",
                storeDirectory.toString(), context, e)
        }
        Path candidate = base.resolve(normalizedRelative).normalize()
        if (!candidate.startsWith(base) || candidate == base) {
            throw new PlanStoreException(
                "Store index file entry outside store directory: ${raw}",
                candidate.toString(), context)
        }

        // Existing targets: reject symlink escape and real-path escape
        if (Files.exists(candidate, java.nio.file.LinkOption.NOFOLLOW_LINKS) ||
            Files.exists(candidate)) {
            try {
                if (Files.isSymbolicLink(candidate)) {
                    Path real = candidate.toRealPath()
                    Path realBase = realBase(base)
                    if (!real.startsWith(realBase)) {
                        throw new PlanStoreException(
                            "Store index file entry symlink escapes store: ${raw}",
                            real.toString(), context)
                    }
                } else if (Files.exists(candidate)) {
                    Path real = candidate.toRealPath()
                    Path realBase = realBase(base)
                    if (!real.startsWith(realBase)) {
                        throw new PlanStoreException(
                            "Store index file entry real path escapes store: ${raw}",
                            real.toString(), context)
                    }
                }
                // Parent containment for nested link components
                Path parent = candidate.parent
                if (parent != null && Files.exists(parent)) {
                    Path realParent = parent.toRealPath()
                    Path realBase = realBase(base)
                    if (!realParent.startsWith(realBase) && realParent != realBase) {
                        throw new PlanStoreException(
                            "Store index file parent escapes store: ${raw}",
                            realParent.toString(), context)
                    }
                }
            } catch (PlanStoreException e) {
                throw e
            } catch (Exception e) {
                throw new PlanStoreException(
                    "Failed to validate store path containment for ${raw}: ${e.message}",
                    candidate.toString(), context, e)
            }
        }
        return candidate
    }

    private static Path realBase(Path base) {
        try {
            if (Files.exists(base)) {
                return base.toRealPath()
            }
        } catch (Exception ignored) {
        }
        return base.toAbsolutePath().normalize()
    }
}
