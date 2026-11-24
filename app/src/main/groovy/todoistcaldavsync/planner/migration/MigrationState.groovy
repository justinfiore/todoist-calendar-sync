package todoistcaldavsync.planner.migration

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.nio.file.*
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest

/** Private, atomic handoff evidence. Absence preserves installations not using migration. */
final class MigrationState {
    final Path directory
    MigrationState(Path applicationDirectory) { directory = applicationDirectory.resolve('migration') }

    Map read() {
        Path file = directory.resolve('state.json')
        if (!Files.exists(file)) {
            if (Files.exists(directory)) throw new IllegalStateException('migration directory lacks its state; retain exports and reconcile before writes')
            return null
        }
        Map state = readJson(file)
        Map content = new LinkedHashMap(state)
        content.remove('checksum')
        if (state.version != 1 || !(state.stage in ['INVENTORIED', 'DELETION_APPROVED', 'LEGACY_DELETED',
            'RECONSTRUCTING', 'VERIFIED', 'PLANNING_RELEASED', 'ROLLING_BACK', 'ROLLED_BACK']) ||
            state.checksum != hash(content) ||
            !(state.manifest instanceof Map) || state.manifestHash != hash(state.manifest) ||
            !(state.deletions instanceof Map) || !(state.created instanceof Map)) {
            throw new IllegalStateException('corrupt migration evidence; restore/reconcile, never reset')
        }
        state
    }

    void save(Map state) {
        Map content = new LinkedHashMap(state)
        content.remove('checksum')
        writeJson(directory.resolve('state.json'), content + [checksum: hash(content)])
    }

    void requireAuthority(String authority) {
        Map state = read()
        if (state == null) return
        if (state.stage == 'PLANNING_RELEASED' ||
            (authority == 'sync' && state.stage == 'VERIFIED' && state.syncReleased == true)) return
        throw new IllegalStateException("migration ${state.stage} holds ${authority}; explicit verified release required")
    }

    static Map readJson(Path path) {
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path)) throw new IllegalStateException('regular evidence file required')
        def value = new JsonSlurper().parseText(Files.readString(path))
        if (!(value instanceof Map)) throw new IllegalStateException('object evidence required')
        value as Map
    }

    static void writeJson(Path file, Map value) {
        Files.createDirectories(file.parent)
        Files.setPosixFilePermissions(file.parent, PosixFilePermissions.fromString('rwx------'))
        Path temporary = Files.createTempFile(file.parent, '.migration-', '.json')
        try {
            Files.setPosixFilePermissions(temporary, PosixFilePermissions.fromString('rw-------'))
            Files.writeString(temporary, JsonOutput.prettyPrint(JsonOutput.toJson(value)))
            try { Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            catch (AtomicMoveNotSupportedException ignored) { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING) }
        } finally { Files.deleteIfExists(temporary) }
    }

    static String hash(Object value) {
        MessageDigest.getInstance('SHA-256').digest(JsonOutput.toJson(canonical(value)).getBytes('UTF-8')).encodeHex().toString()
    }

    private static Object canonical(Object value) {
        if (value instanceof Map) return new TreeMap((value as Map).collectEntries { k, v -> [(k.toString()): canonical(v)] })
        if (value instanceof Collection) return value.collect { canonical(it) }
        value
    }
}
