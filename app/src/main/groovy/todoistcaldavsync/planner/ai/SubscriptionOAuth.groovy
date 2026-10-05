package todoistcaldavsync.planner.ai

import todoistcaldavsync.planner.config.PlannerConfig

import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import java.util.function.Supplier

interface SubscriptionOAuthTransport {
    OAuthHttpResponse postForm(URI endpoint, Map<String,String> fields, Duration timeout)
    OAuthHttpResponse get(URI endpoint, Map<String,String> headers, Duration timeout)
}

final class OAuthHttpResponse {
    final int status
    final byte[] body
    final Map<String,List<String>> headers
    OAuthHttpResponse(int status, byte[] body, Map<String,List<String>> headers = [:]) {
        this.status = status; this.body = (body ?: new byte[0]).clone()
        this.headers = Collections.unmodifiableMap(new LinkedHashMap<>(headers ?: [:]))
    }
}

final class JdkSubscriptionOAuthTransport implements SubscriptionOAuthTransport {
    private final HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
        .connectTimeout(Duration.ofSeconds(10)).build()
    private final int maxBytes
    JdkSubscriptionOAuthTransport(int maxBytes = 65_536) { this.maxBytes = maxBytes }

    @Override OAuthHttpResponse postForm(URI endpoint, Map<String,String> fields, Duration timeout) {
        requireHttps(endpoint)
        String form = fields.collect { k,v -> enc(k) + '=' + enc(v) }.join('&')
        send(HttpRequest.newBuilder(endpoint).timeout(timeout).header('Accept', 'application/json')
            .header('Content-Type', 'application/x-www-form-urlencoded')
            .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8)).build())
    }
    @Override OAuthHttpResponse get(URI endpoint, Map<String,String> headers, Duration timeout) {
        requireHttps(endpoint)
        def builder = HttpRequest.newBuilder(endpoint).timeout(timeout).GET()
        headers.each { k,v -> builder.header(k,v) }
        send(builder.build())
    }
    private OAuthHttpResponse send(HttpRequest request) {
        def response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
        byte[] bytes
        response.body().withCloseable { bytes = it.readNBytes(maxBytes + 1) }
        if (bytes.length > maxBytes) throw new SubscriptionAuthException('provider_response_too_large')
        new OAuthHttpResponse(response.statusCode(), bytes, response.headers().map())
    }
    private static String enc(Object value) { URLEncoder.encode(value?.toString() ?: '', StandardCharsets.UTF_8) }
    private static void requireHttps(URI uri) {
        if (uri?.scheme != 'https' || uri.userInfo || uri.fragment || !(uri.port in [-1,443])) {
            throw new SubscriptionAuthException('provider_endpoint_unsafe')
        }
    }
}

interface SubscriptionProviderAdapter {
    String provider()
    String revision()
    String issuer()
    Set<String> requiredScopes()
    URI refreshEndpoint(SubscriptionCredential credential)
    URI revocationEndpoint(SubscriptionCredential credential)
    Map<String,String> refreshForm(SubscriptionCredential credential)
    Map<String,String> revocationForm(SubscriptionCredential credential)
    void validate(SubscriptionCredential credential)
}

final class OpenAiSiwcAdapter implements SubscriptionProviderAdapter {
    static final String REVISION = 'openai-siwc-2026-10-03-v1'
    static final Set<String> SCOPES = ['openid','profile','email','offline_access',
        'resource.invoke','chatgpt.tokens.use.direct'] as Set
    String provider() { 'codex' }
    String revision() { REVISION }
    String issuer() { 'https://auth.openai.com' }
    Set<String> requiredScopes() { SCOPES }
    URI refreshEndpoint(SubscriptionCredential ignored) { URI.create('https://auth.openai.com/api/accounts/oauth/token') }
    URI revocationEndpoint(SubscriptionCredential ignored) { URI.create('https://auth.openai.com/oauth/revoke') }
    Map<String,String> refreshForm(SubscriptionCredential c) {
        [grant_type:'refresh_token', client_id:c.clientId, refresh_token:c.refreshToken,
         resource:'https://api.openai.com/v1']
    }
    Map<String,String> revocationForm(SubscriptionCredential c) {
        [token:c.refreshToken, token_type_hint:'refresh_token', client_id:c.clientId]
    }
    void validate(SubscriptionCredential c) {
        if (c.provider != provider() || c.adapterRevision != revision() || c.issuer != issuer() ||
            c.clientId == 'dynamic_agent_client' || !(c.clientId ==~ /^[A-Za-z0-9._-]{3,256}$/) ||
            !(c.hostId ==~ /^(urn:uuid:[0-9a-fA-F-]{36}|urn:ietf:params:oauth:jwk-thumbprint:[A-Za-z0-9_-]+|did:key:[A-Za-z0-9._:-]+)$/) ||
            !c.scopes.containsAll(requiredScopes())) throw new SubscriptionAuthException('credential_incompatible')
    }
}

/** Implemented for hermetic compatibility tests; release remains gated on xAI registration permission. */
final class XaiDeviceOAuthAdapter implements SubscriptionProviderAdapter {
    static final String REVISION = 'xai-device-responses-hermetic-v1'
    static final String CLIENT_ID = 'b1a00492-073a-47ea-816f-4c329264a828'
    static final Set<String> SCOPES = ['openid','profile','email','offline_access','grok-cli:access','api:access'] as Set
    String provider() { 'grok' }
    String revision() { REVISION }
    String issuer() { 'https://auth.x.ai' }
    Set<String> requiredScopes() { SCOPES }
    URI refreshEndpoint(SubscriptionCredential ignored) { URI.create('https://auth.x.ai/oauth2/token') }
    URI revocationEndpoint(SubscriptionCredential ignored) { null }
    Map<String,String> refreshForm(SubscriptionCredential c) {
        [grant_type:'refresh_token', client_id:CLIENT_ID, refresh_token:c.refreshToken]
    }
    Map<String,String> revocationForm(SubscriptionCredential ignored) { [:] }
    void validate(SubscriptionCredential c) {
        if (c.provider != provider() || c.adapterRevision != revision() || c.issuer != issuer() ||
            c.clientId != CLIENT_ID || !c.scopes.containsAll(requiredScopes())) {
            throw new SubscriptionAuthException('credential_incompatible')
        }
    }
}

final class SubscriptionCredentialService {
    private final SubscriptionCredentialStore store
    private final SubscriptionProviderAdapter adapter
    private final SubscriptionOAuthTransport transport
    private final Supplier<Instant> clock
    private final Duration refreshWindow
    private final Duration timeout

    SubscriptionCredentialService(SubscriptionCredentialStore store, SubscriptionProviderAdapter adapter,
                                  SubscriptionOAuthTransport transport = new JdkSubscriptionOAuthTransport(),
                                  Supplier<Instant> clock = { Instant.now() },
                                  Duration refreshWindow = Duration.ofMinutes(2),
                                  Duration timeout = Duration.ofSeconds(20)) {
        if ([store,adapter,transport,clock].any { it == null } || store.provider != adapter.provider()) {
            throw new IllegalArgumentException('matching credential service components are required')
        }
        this.store=store;this.adapter=adapter;this.transport=transport;this.clock=clock
        this.refreshWindow=refreshWindow;this.timeout=timeout
    }

    String accessToken() {
        store.withLock {
            SubscriptionCredential current = store.loadUnlocked().orElseThrow {
                new SubscriptionAuthException('credential_missing', true)
            }
            adapter.validate(current)
            Instant now = clock.get()
            Instant permitted = current.earliestRefreshAt ?: Instant.EPOCH
            if (current.expiresAt.isAfter(now.plus(refreshWindow))) return current.accessToken
            if (now.isBefore(permitted)) {
                if(current.expiresAt.isAfter(now))return current.accessToken
                throw new SubscriptionAuthException('refresh_not_yet_permitted')
            }
            OAuthHttpResponse response
            try { response = transport.postForm(adapter.refreshEndpoint(current), adapter.refreshForm(current), timeout) }
            catch (SubscriptionAuthException e) { throw e }
            catch (Exception ignored) { throw new SubscriptionAuthException('refresh_transport_ambiguous') }
            Map parsed = parse(response)
            if (response.status < 200 || response.status >= 300) {
                String code = parsed.error?.toString()
                boolean terminal = code in ['invalid_grant','invalid_refresh_token','token_expired',
                    'refresh_token_expired','refresh_token_invalidated','refresh_token_reused','invalid_client']
                throw new SubscriptionAuthException(terminal ? 'reauthentication_required' : 'refresh_rejected', terminal)
            }
            SubscriptionCredential rotated
            try { rotated = current.rotated(parsed, now); adapter.validate(rotated) }
            catch (Exception ignored) { throw new SubscriptionAuthException('refresh_response_invalid') }
            store.saveUnlocked(rotated)
            rotated.accessToken
        }
    }

    Map status(boolean remote = false) {
        Optional<SubscriptionCredential> found = store.load()
        if (found.empty) return [state:'absent', refreshOwner:'smartplanner', remoteEntitlement:'not_requested']
        SubscriptionCredential c = found.get(); adapter.validate(c)
        Instant now=clock.get()
        String state = !c.expiresAt.isAfter(now) ? 'expired' :
            (c.expiresAt.isAfter(now.plus(refreshWindow)) ? 'ready' : 'expiring')
        if (!remote) return [state:state, refreshOwner:'smartplanner', generation:c.generation,
            adapterRevision:c.adapterRevision, remoteEntitlement:'not_requested']
        String token=accessToken()
        URI models = adapter.provider() == 'codex' ? URI.create('https://api.openai.com/v1/models') : URI.create('https://api.x.ai/v1/models')
        OAuthHttpResponse probe
        try { probe=transport.get(models,['Authorization':"Bearer ${token}"],timeout) }
        catch(Exception ignored) { throw new SubscriptionAuthException('remote_status_unavailable') }
        finally { token=null }
        if (probe.status == 401) throw new SubscriptionAuthException('reauthentication_required',true)
        if (probe.status == 403) throw new SubscriptionAuthException('entitlement_unavailable')
        if (probe.status < 200 || probe.status >= 300) throw new SubscriptionAuthException('remote_status_unavailable')
        Map catalog=parse(probe)
        Object catalogModels=adapter.provider()=='codex' ? catalog.data : catalog.models
        if (!(catalogModels instanceof Collection)) throw new SubscriptionAuthException('remote_status_invalid')
        [state:'ready', refreshOwner:'smartplanner', generation:store.load().get().generation,
         adapterRevision:c.adapterRevision, remoteEntitlement:'available']
    }

    String logout() {
        Optional<SubscriptionCredential> found = store.load()
        if (found.empty) return 'already_absent'
        boolean revoked = false
        try {
            URI endpoint = adapter.revocationEndpoint(found.get())
            if (adapter.provider() == 'codex') {
                Map discovery=parse(transport.get(URI.create('https://auth.openai.com/.well-known/openid-configuration'),[:],timeout))
                endpoint=URI.create(discovery.revocation_endpoint?.toString())
                if (endpoint.scheme!='https' || endpoint.host!='auth.openai.com' || !(endpoint.port in [-1,443])) {
                    throw new SubscriptionAuthException('revocation_endpoint_unsafe')
                }
            }
            if (endpoint != null) {
                OAuthHttpResponse response = transport.postForm(endpoint, adapter.revocationForm(found.get()), timeout)
                revoked = response.status >= 200 && response.status < 300
            }
        } catch (Exception ignored) { /* local removal remains deterministic */ }
        store.remove()
        revoked ? 'revoked_and_removed' : 'local_removed_revocation_unconfirmed'
    }

    private static Map parse(OAuthHttpResponse response) {
        try { StrictJson.parseObject(response.body) }
        catch (Exception ignored) { [:] }
    }
}
