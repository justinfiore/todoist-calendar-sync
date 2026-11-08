package todoistcaldavsync

import groovy.json.JsonOutput
import groovy.yaml.YamlSlurper
import todoistcaldavsync.planner.adapters.TodoistRestGateway

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant

/** Standalone read-only backup; deliberately does not compose the application. */
final class TodoistTaskExporter {
    static void main(String[] args) {
        if (args.toList() in [['--help'], ['-h']]) {
            println 'Usage: todoist-export.sh --config FILE --output-dir NEW_TIMESTAMPED_DIRECTORY'
            return
        }
        try {
            Map options = [:]
            for (int i = 0; i < args.length; i += 2) {
                if (!(args[i] in ['--config', '--output-dir']) || i + 1 >= args.length || options.containsKey(args[i])) {
                    throw new IllegalArgumentException()
                }
                options[args[i]] = args[i + 1]
            }
            if (options.size() != 2) throw new IllegalArgumentException()
            exportTasks(Path.of(options['--config']), Path.of(options['--output-dir']))
            println 'Todoist backup complete. Private files and integrity metadata written.'
        } catch (Exception ignored) {
            // Never print paths, provider bodies, task data, config contents, or exception causes.
            System.err.println('Todoist backup failed; no complete snapshot was published. Check configuration, authentication, destination, and provider availability. Use --help for usage.')
            System.exit(1)
        }
    }

    /** Environment resolver is injectable for offline contract tests. HTTP is HTTPS-only by default. */
    static Map exportTasks(Path config, Path output, Closure environment = { String name -> System.getenv(name) },
                           boolean allowInsecureHttp = false) {
        boolean created = false
        try {
            Map root = new YamlSlurper().parse(config.toFile()) as Map
            Map todoist = root.planner?.integration?.todoist ?: [:]
            String legacy = root.todoist?.accessToken?.toString()?.trim()
            String explicitEnv = todoist.token_env?.toString()?.trim()
            String envName = explicitEnv ?: 'TODOIST_ACCESS_TOKEN'
            // Legacy-only configs must not accidentally switch to an ambient environment account.
            String token = explicitEnv || !legacy ? environment.call(envName)?.toString()?.trim() : legacy
            if (!token || (explicitEnv && legacy && token != legacy)) {
                throw new IllegalArgumentException()
            }
            Map gatewayOptions = [tokenOverride: token, includeProjectNames: false,
                baseUrl: todoist.base_url ?: TodoistRestGateway.DEFAULT_BASE_URL,
                allowInsecureHttp: allowInsecureHttp,
                // Full Sync is one response, so permit a larger bounded inventory than REST pages.
                maxResponseBytes: todoist.max_response_bytes ?: 67_108_864L,
                maxPages: todoist.max_pages ?: TodoistRestGateway.DEFAULT_MAX_PAGES]
            if (todoist.timeout) gatewayOptions.timeout = Duration.parse(todoist.timeout.toString())
            def gateway = new TodoistRestGateway(gatewayOptions)

            // Atomic creation refuses existing files/directories/symlinks. Parent must already exist.
            Files.createDirectory(output, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString('rwx------')))
            created = true
            String started = Instant.now().toString()
            String restStarted = Instant.now().toString()
            List<Map> tasks = gateway.fetchTasks()
            String restEnded = Instant.now().toString()
            String syncStarted = Instant.now().toString()
            def sync = gateway.syncItems('*')
            String syncEnded = Instant.now().toString()
            Map inventories = [
                'active-tasks.json': [records: tasks, scope: 'All active tasks, raw REST records; no filters or project enrichment',
                                      capture_started_at: restStarted, capture_ended_at: restEnded],
                'sync-items.json': [records: sync.items, scope: 'Separately obtained full read-only Sync items; not completed history',
                                    capture_started_at: syncStarted, capture_ended_at: syncEnded,
                                    full_sync_date_utc: sync.fullSyncDateUtc]
            ]
            Map files = [:]
            inventories.each { String name, Map inventory ->
                byte[] bytes = (JsonOutput.prettyPrint(JsonOutput.toJson(inventory.records)) + '\n').getBytes('UTF-8')
                writePrivate(output.resolve(name), bytes)
                files[name] = inventory.findAll { k, v -> k != 'records' } + [
                    count: inventory.records.size(), bytes: bytes.length,
                    sha256: MessageDigest.getInstance('SHA-256').digest(bytes).encodeHex().toString()]
            }
            Map manifest = [format_version: 1, status: 'complete', capture_started_at: started,
                capture_ended_at: Instant.now().toString(),
                scope: 'Active-task backup, not a complete account archive; independently captured nontransactional inventories',
                files: files]
            // This is the only success marker and is written after both inventories succeed.
            writePrivate(output.resolve('manifest.json'), (JsonOutput.prettyPrint(JsonOutput.toJson(manifest)) + '\n').getBytes('UTF-8'))
            return manifest
        } catch (Exception ignored) {
            if (created) {
                // Keep the reserved directory, but never leave complete-looking data after failure.
                ['manifest.json', 'active-tasks.json', 'sync-items.json'].each { String name ->
                    try { Files.deleteIfExists(output.resolve(name)) } catch (Exception suppressed) { }
                }
            }
            throw new IllegalStateException('Todoist backup failed; no complete snapshot was published')
        }
    }

    private static void writePrivate(Path path, byte[] bytes) {
        Files.newByteChannel(path, EnumSet.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE),
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString('rw-------'))).withCloseable { channel ->
            def buffer = java.nio.ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) channel.write(buffer)
        }
    }
}
