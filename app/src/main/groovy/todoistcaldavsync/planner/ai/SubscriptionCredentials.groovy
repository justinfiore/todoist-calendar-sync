package todoistcaldavsync.planner.ai

import groovy.json.JsonOutput

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/** Versioned, provider-owned OAuth record. Token values must never be rendered or logged. */
final class SubscriptionCredential {
    static final int FORMAT_VERSION = 1
    final String provider
    final String adapterRevision
    final long generation
    final String issuer
    final String clientId
    final String hostId
    final String subject
    final String accessToken
    final String refreshToken
    final String idToken
    final Instant expiresAt
    final Instant earliestRefreshAt
    final Set<String> scopes

    SubscriptionCredential(Map values) {
        values = values ?: [:]
        provider = required(values.provider)
        adapterRevision = required(values.adapterRevision ?: values.adapter_revision)
        generation = (values.generation ?: 1L) as long
        issuer = required(values.issuer)
        clientId = required(values.clientId ?: values.client_id)
        hostId = optional(values.hostId ?: values.ext_agent_host_id)
        subject = optional(values.subject)
        accessToken = required(values.accessToken ?: values.access_token)
        refreshToken = required(values.refreshToken ?: values.refresh_token)
        idToken = optional(values.idToken ?: values.id_token)
        expiresAt = instant(values.expiresAt ?: values.expires_at)
        earliestRefreshAt = optionalInstant(values.earliestRefreshAt ?: values.earliest_refresh_at)
        Collection rawScopes = values.scopes instanceof Collection ? values.scopes as Collection :
            values.scope?.toString()?.split(/\s+/)?.findAll { it } ?: []
        scopes = Collections.unmodifiableSet(new LinkedHashSet<>(rawScopes.collect { required(it) }))
        if (!(provider in ['codex', 'grok']) || generation < 1 || expiresAt == null || scopes.empty) {
            throw new IllegalArgumentException('invalid subscription credential')
        }
    }

    SubscriptionCredential rotated(Map tokenResponse, Instant now) {
        String access = required(tokenResponse.access_token)
        String refresh = required(tokenResponse.refresh_token)
        long seconds
        try { seconds = Long.parseLong(tokenResponse.expires_in?.toString()) }
        catch (Exception ignored) { throw new IllegalArgumentException('invalid token response') }
        if (seconds < 1 || seconds > 86400) throw new IllegalArgumentException('invalid token response')
        Collection returned = tokenResponse.scope == null ? scopes : tokenResponse.scope.toString().split(/\s+/).findAll { it }
        new SubscriptionCredential(provider: provider, adapterRevision: adapterRevision,
            generation: generation + 1, issuer: issuer, clientId: clientId, hostId: hostId,
            subject: subject, accessToken: access, refreshToken: refresh,
            idToken: tokenResponse.id_token ?: idToken, expiresAt: now.plusSeconds(seconds),
            earliestRefreshAt: tokenResponse.earliest_refresh_at ?: earliestRefreshAt,
            scopes: returned)
    }

    Map persisted() {
        [version: FORMAT_VERSION, provider: provider, adapter_revision: adapterRevision,
         generation: generation, issuer: issuer, client_id: clientId,
         ext_agent_host_id: hostId, subject: subject, access_token: accessToken,
         refresh_token: refreshToken, id_token: idToken, expires_at: expiresAt.toString(),
         earliest_refresh_at: earliestRefreshAt?.toString(), scopes: scopes as List]
    }

    @Override String toString() { "SubscriptionCredential(${provider},generation=${generation})" }

    private static String required(def value) {
        String text = value?.toString()
        if (!text) throw new IllegalArgumentException('missing credential field')
        text
    }
    private static String optional(def value) { value == null || value.toString().isEmpty() ? null : value.toString() }
    private static Instant instant(def value) {
        if (value instanceof Instant) return value as Instant
        try { Instant.parse(required(value)) } catch (Exception ignored) { throw new IllegalArgumentException('invalid credential instant') }
    }
    private static Instant optionalInstant(def value) {
        if (value == null) return null
        if (value instanceof Number) return Instant.ofEpochSecond((value as Number).longValue())
        String text=value.toString()
        if(text==~ /^\d+$/)return Instant.ofEpochSecond(Long.parseLong(text))
        instant(value)
    }
}

/** Private per-provider store with a cross-process lock and crash-safe replacement. */
final class SubscriptionCredentialStore {
    private static final int MAX_BYTES = 65_536
    private static final Map<String,Object> JVM_LOCKS = new ConcurrentHashMap<>()
    final Path authRoot
    final String provider
    private final Path directory
    private final Closure replacement

    SubscriptionCredentialStore(Path authRoot, String provider, Closure replacement = null) {
        if (authRoot == null || !authRoot.isAbsolute() || !(provider in ['codex', 'grok'])) {
            throw new IllegalArgumentException('absolute auth root and known provider are required')
        }
        this.authRoot = authRoot.normalize()
        this.provider = provider
        this.directory = this.authRoot.resolve('providers').resolve(provider).normalize()
        this.replacement = replacement ?: { Path source,Path target ->
            try { Files.move(source,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING) }
            catch(AtomicMoveNotSupportedException ignored) { Files.move(source,target,StandardCopyOption.REPLACE_EXISTING) }
        }
        if (!directory.startsWith(this.authRoot)) throw new IllegalArgumentException('provider path escapes auth root')
    }

    Optional<SubscriptionCredential> load() { withLock { loadUnlocked() } }
    void save(SubscriptionCredential value) { withLock { saveUnlocked(value); null } }
    boolean remove() { withLock { Files.deleteIfExists(credentialPath()) } }

    <T> T withLock(Closure<T> action) {
        prepareDirectory()
        String key = lockPath().toAbsolutePath().normalize().toString()
        Object monitor = JVM_LOCKS.computeIfAbsent(key) { new Object() }
        synchronized (monitor) {
            if (Files.exists(lockPath(), LinkOption.NOFOLLOW_LINKS)) validateRegularPrivateFile(lockPath())
            FileChannel channel = FileChannel.open(lockPath(), StandardOpenOption.CREATE,
                StandardOpenOption.READ, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)
            setPermissions(lockPath(), 'rw-------')
            try (channel; FileLock ignored = channel.lock()) { action.call() }
        }
    }

    Optional<SubscriptionCredential> loadUnlocked() {
        Path file = credentialPath()
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return Optional.empty()
        validateRegularPrivateFile(file)
        if (Files.size(file) > MAX_BYTES) throw safe('credential_too_large')
        try {
            Map parsed = StrictJson.parseObject(Files.readString(file, StandardCharsets.UTF_8))
            if (parsed.version != SubscriptionCredential.FORMAT_VERSION || parsed.provider != provider) throw safe('credential_malformed')
            return Optional.of(new SubscriptionCredential(parsed))
        } catch (SubscriptionAuthException e) { throw e }
        catch (Exception ignored) { throw safe('credential_malformed') }
    }

    void saveUnlocked(SubscriptionCredential value) {
        if (value == null || value.provider != provider) throw safe('credential_provider_mismatch')
        prepareDirectory()
        byte[] bytes = JsonOutput.toJson(value.persisted()).getBytes(StandardCharsets.UTF_8)
        if (bytes.length > MAX_BYTES) throw safe('credential_too_large')
        Path temp = Files.createTempFile(directory, '.credential-', '.tmp')
        try {
            setPermissions(temp, 'rw-------')
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
                channel.write(ByteBuffer.wrap(bytes)); channel.force(true)
            }
            replacement.call(temp,credentialPath())
            setPermissions(credentialPath(), 'rw-------')
            try (FileChannel dir = FileChannel.open(directory, StandardOpenOption.READ)) { dir.force(true) }
        } catch (SubscriptionAuthException e) { throw e }
        catch (Exception ignored) { throw safe('credential_write_failed') }
        finally { Files.deleteIfExists(temp) }
    }

    private void prepareDirectory() {
        rejectSymlinkComponents(authRoot)
        boolean rootExisted=Files.exists(authRoot,LinkOption.NOFOLLOW_LINKS)
        boolean providersExisted=Files.exists(authRoot.resolve('providers'),LinkOption.NOFOLLOW_LINKS)
        boolean directoryExisted=Files.exists(directory,LinkOption.NOFOLLOW_LINKS)
        if(rootExisted)validatePrivateDirectory(authRoot)
        if(providersExisted)validatePrivateDirectory(authRoot.resolve('providers'))
        if(directoryExisted)validatePrivateDirectory(directory)
        Files.createDirectories(directory)
        rejectSymlinkComponents(directory)
        setPermissions(authRoot, 'rwx------')
        setPermissions(authRoot.resolve('providers'), 'rwx------')
        setPermissions(directory, 'rwx------')
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) throw safe('credential_path_unsafe')
    }

    private static void validatePrivateDirectory(Path path) {
        if(Files.isSymbolicLink(path)||!Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS))throw safe('credential_path_unsafe')
        if(supportsPosix(path)) {
            Set<PosixFilePermission> permissions=Files.getPosixFilePermissions(path,LinkOption.NOFOLLOW_LINKS)
            if(permissions.any{it.name().startsWith('GROUP_')||it.name().startsWith('OTHERS_')}) {
                throw safe('credential_permissions_unsafe')
            }
            Path home=Path.of(System.getProperty('user.home')).toAbsolutePath().normalize()
            if(Files.exists(home)&&Files.getOwner(path,LinkOption.NOFOLLOW_LINKS)!=Files.getOwner(home,LinkOption.NOFOLLOW_LINKS)) {
                throw safe('credential_owner_unsafe')
            }
        }
    }

    private void validateRegularPrivateFile(Path file) {
        if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw safe('credential_path_unsafe')
        if (supportsPosix(file)) {
            Set<PosixFilePermission> actual = Files.getPosixFilePermissions(file, LinkOption.NOFOLLOW_LINKS)
            if (actual != PosixFilePermissions.fromString('rw-------')) throw safe('credential_permissions_unsafe')
            if (Files.getOwner(file, LinkOption.NOFOLLOW_LINKS) != Files.getOwner(directory, LinkOption.NOFOLLOW_LINKS)) {
                throw safe('credential_owner_unsafe')
            }
            try {
                Number links = Files.getAttribute(file, 'unix:nlink', LinkOption.NOFOLLOW_LINKS) as Number
                if (links.longValue() != 1L) throw safe('credential_hardlink_unsafe')
            } catch (UnsupportedOperationException ignored) {}
        }
    }

    private static void rejectSymlinkComponents(Path path) {
        Path absolute = path.toAbsolutePath().normalize(); Path cursor = absolute.root
        absolute.each { part -> cursor = cursor.resolve(part); if (Files.isSymbolicLink(cursor)) throw safe('credential_path_unsafe') }
    }
    private static boolean supportsPosix(Path path) { Files.getFileStore(path).supportsFileAttributeView('posix') }
    private static void setPermissions(Path path, String mode) {
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS) && supportsPosix(path)) {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(mode))
        }
    }
    private Path credentialPath() { directory.resolve('credential.json') }
    private Path lockPath() { directory.resolve('credential.lock') }
    private static SubscriptionAuthException safe(String code) { new SubscriptionAuthException(code) }
}

final class SubscriptionAuthException extends RuntimeException {
    private static final Set<String> SAFE_CODES = [
        'account_identity_mismatch','authentication_failed','client_registration_mismatch',
        'credential_hardlink_unsafe','credential_incompatible','credential_malformed','credential_missing',
        'credential_owner_unsafe','credential_path_unsafe','credential_permissions_unsafe',
        'credential_provider_mismatch','credential_too_large','credential_write_failed',
        'device_authorization_invalid','device_token_rejected','entitlement_unavailable',
        'host_id_unsafe','host_id_write_failed','id_token_invalid','login_cancelled','login_denied',
        'login_expired','login_state_mismatch','login_timeout','plan_permission_not_granted',
        'provider_endpoint_unsafe','provider_response_invalid','provider_response_too_large',
        'reauthentication_required','refresh_not_yet_permitted','refresh_rejected',
        'refresh_response_invalid','refresh_transport_ambiguous','registration_incomplete',
        'remote_status_invalid','remote_status_unavailable','revocation_endpoint_unsafe','scope_mismatch',
        'token_exchange_rejected','token_response_invalid'
    ] as Set
    final String code
    final boolean reauthenticationRequired
    SubscriptionAuthException(String code, boolean reauthenticationRequired = false) {
        super(safeCode(code)); this.code = safeCode(code); this.reauthenticationRequired = reauthenticationRequired
    }
    @Override String toString() { "SubscriptionAuthException(${code})" }
    private static String safeCode(String candidate) {
        SAFE_CODES.contains(candidate) ? candidate : 'authentication_failed'
    }
}
