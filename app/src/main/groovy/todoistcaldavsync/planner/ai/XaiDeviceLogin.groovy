package todoistcaldavsync.planner.ai

import java.net.URI
import java.time.Duration
import java.time.Instant

/**
 * Hermes-style RFC 8628 implementation using xAI's source-visible public client.
 * SmartPlanner owns the resulting rotating credential and does not reuse Grok Build state.
 */
final class XaiDeviceLogin {
    private static final URI DEVICE = URI.create('https://auth.x.ai/oauth2/device/code')
    private static final URI DISCOVERY = URI.create('https://auth.x.ai/.well-known/openid-configuration')
    private final SubscriptionCredentialStore store
    private final SubscriptionOAuthTransport transport
    private final Closure sleeper
    private final Closure<Instant> clock

    XaiDeviceLogin(SubscriptionCredentialStore store, SubscriptionOAuthTransport transport,
                   Closure sleeper = { long millis -> Thread.sleep(millis) },
                   Closure<Instant> clock = { Instant.now() }) {
        if ([store,transport,sleeper,clock].any { it == null } || store.provider!='grok') {
            throw new IllegalArgumentException('xAI device login components are required')
        }
        this.store=store;this.transport=transport;this.sleeper=sleeper;this.clock=clock
    }

    void login(Appendable out) {
        def adapter=new XaiDeviceOAuthAdapter()
        Map discovery=parse(transport.get(DISCOVERY,[:],Duration.ofSeconds(20)))
        URI token=validated(discovery.token_endpoint)
        OAuthHttpResponse start=transport.postForm(DEVICE,[client_id:XaiDeviceOAuthAdapter.CLIENT_ID,
            scope:XaiDeviceOAuthAdapter.SCOPES.join(' ')],Duration.ofSeconds(20))
        Map device=parse(start)
        if(start.status<200 || start.status>=300 || !device.device_code || !device.user_code ||
            !(device.verification_uri ?: device.verification_uri_complete)) throw new SubscriptionAuthException('device_authorization_invalid')
        URI verification=validated(device.verification_uri_complete ?: device.verification_uri)
        out.append('Open ').append(verification.toString())
            .append(' and enter code ').append(device.user_code.toString()).append('\n')
        long interval=Math.max(1L,(device.interval ?: 5) as long)
        Instant deadline=clock.call().plusSeconds(Math.min(900L,Math.max(1L,(device.expires_in ?: 600) as long)))
        while(clock.call().isBefore(deadline)) {
            try { sleeper.call(interval*1000L) }
            catch(InterruptedException interrupted) {
                Thread.currentThread().interrupt()
                throw new SubscriptionAuthException('login_cancelled')
            }
            OAuthHttpResponse response=transport.postForm(token,[grant_type:'urn:ietf:params:oauth:grant-type:device_code',
                device_code:device.device_code.toString(),client_id:XaiDeviceOAuthAdapter.CLIENT_ID],Duration.ofSeconds(20))
            Map body=parse(response)
            if(response.status>=200 && response.status<300) {
                Set<String> scopes=(body.scope?.toString()?.split(/\s+/)?.findAll{it} ?: []) as Set
                if(!scopes.containsAll(XaiDeviceOAuthAdapter.SCOPES)) throw new SubscriptionAuthException('scope_mismatch')
                long expires
                try{expires=Long.parseLong(body.expires_in?.toString())}catch(Exception ignored){throw new SubscriptionAuthException('token_response_invalid')}
                if(expires<1L || expires>86_400L)throw new SubscriptionAuthException('token_response_invalid')
                try {
                    SubscriptionCredential record=new SubscriptionCredential(provider:'grok',adapterRevision:adapter.revision(),generation:1,
                        issuer:adapter.issuer(),clientId:XaiDeviceOAuthAdapter.CLIENT_ID,accessToken:body.access_token,
                        refreshToken:body.refresh_token,idToken:body.id_token,expiresAt:clock.call().plusSeconds(expires),scopes:scopes)
                    adapter.validate(record);store.save(record);return
                } catch(SubscriptionAuthException e) { throw e }
                catch(Exception ignored) { throw new SubscriptionAuthException('token_response_invalid') }
            }
            switch(body.error?.toString()) {
                case 'authorization_pending': break
                case 'slow_down': interval+=5L;break
                case 'access_denied': throw new SubscriptionAuthException('login_denied')
                case 'expired_token': throw new SubscriptionAuthException('login_expired')
                default: throw new SubscriptionAuthException('device_token_rejected')
            }
        }
        throw new SubscriptionAuthException('login_timeout')
    }

    private static URI validated(def raw) {
        URI endpoint=URI.create(raw?.toString())
        String host=endpoint.host?.toLowerCase(Locale.ROOT)
        if(endpoint.scheme!='https' || !(endpoint.port in [-1,443]) || host!='auth.x.ai' ||
            endpoint.userInfo || endpoint.fragment) throw new SubscriptionAuthException('provider_endpoint_unsafe')
        endpoint
    }
    private static Map parse(OAuthHttpResponse response) {
        if(response.body.length>65_536) throw new SubscriptionAuthException('provider_response_too_large')
        try{StrictJson.parseObject(response.body)}
        catch(Exception ignored){throw new SubscriptionAuthException('provider_response_invalid')}
    }
}
