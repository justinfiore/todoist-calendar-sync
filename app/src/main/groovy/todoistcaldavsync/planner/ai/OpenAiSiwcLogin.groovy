package todoistcaldavsync.planner.ai

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.crypto.RSASSAVerifier
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jwt.SignedJWT
import com.sun.net.httpserver.HttpServer

import java.awt.Desktop
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/** Official OpenAI dynamic-agent authorization-code/PKCE flow with a 127.0.0.1 callback. */
final class OpenAiSiwcLogin {
    private static final URI AUTHORIZE = URI.create('https://auth.openai.com/api/accounts/authorize')
    private static final URI TOKEN = URI.create('https://auth.openai.com/api/accounts/oauth/token')
    private static final URI DISCOVERY = URI.create('https://auth.openai.com/.well-known/openid-configuration')
    private static final String RESOURCE = 'https://api.openai.com/v1'
    private final SubscriptionCredentialStore store
    private final SubscriptionOAuthTransport transport
    private final Duration timeout
    private final Closure browserOpener
    private final SecureRandom random

    OpenAiSiwcLogin(SubscriptionCredentialStore store,
                    SubscriptionOAuthTransport transport = new JdkSubscriptionOAuthTransport(),
                    Duration timeout = Duration.ofMinutes(5),
                    Closure browserOpener = { URI uri ->
                        if (Desktop.isDesktopSupported()) Desktop.desktop.browse(uri)
                    }, SecureRandom random = new SecureRandom()) {
        if ([store,transport,timeout,browserOpener,random].any { it == null } || store.provider != 'codex') {
            throw new IllegalArgumentException('OpenAI SIWC login components are required')
        }
        this.store=store;this.transport=transport;this.timeout=timeout;this.browserOpener=browserOpener;this.random=random
    }

    void login(Appendable out) {
        String state=randomValue(32), nonce=randomValue(32), verifier=randomValue(64)
        String challenge=Base64.urlEncoder.withoutPadding().encodeToString(
            MessageDigest.getInstance('SHA-256').digest(verifier.getBytes(StandardCharsets.US_ASCII)))
        SubscriptionCredential previous=store.load().orElse(null)
        if(previous!=null)new OpenAiSiwcAdapter().validate(previous)
        String hostId = store.withLock { hostIdUnlocked() }
        ArrayBlockingQueue<Map> callback = new ArrayBlockingQueue<>(1)
        HttpServer server = HttpServer.create(new InetSocketAddress('127.0.0.1',0),0)
        server.createContext('/auth/callback') { exchange ->
            Map params=parseQuery(exchange.requestURI.rawQuery)
            callback.offer(params)
            byte[] message='Authentication received. Return to SmartPlanner.'.getBytes(StandardCharsets.UTF_8)
            exchange.sendResponseHeaders(200,message.length);exchange.responseBody.withCloseable { it.write(message) }
        }
        server.start()
        try {
            URI redirect=URI.create("http://127.0.0.1:${server.address.port}/auth/callback")
            String requestedClient=previous?.clientId ?: 'dynamic_agent_client'
            Map<String,String> query=[client_id:requestedClient,ext_agent_host_id:hostId,response_type:'code',redirect_uri:redirect.toString(),
                scope:OpenAiSiwcAdapter.SCOPES.join(' '),resource:RESOURCE,state:state,nonce:nonce,
                code_challenge_method:'S256',code_challenge:challenge]
            if(previous==null)query.agent_name_hint='SmartPlanner'
            else if(previous.idToken)query.id_token_hint=previous.idToken
            URI authorization=URI.create(AUTHORIZE.toString()+'?'+encode(query))
            out.append('Continue with ChatGPT in your browser.\n')
            // A returning URL can contain id_token_hint and must never be printed or logged.
            if(previous==null)out.append(authorization.toString()).append('\n')
            browserOpener.call(authorization)
            Map result=callback.poll(timeout.toMillis(),TimeUnit.MILLISECONDS)
            if (result == null) throw new SubscriptionAuthException('login_timeout')
            if (result.state != state) throw new SubscriptionAuthException('login_state_mismatch')
            if (result.error) throw new SubscriptionAuthException('login_denied')
            String issued=(result.client_id ?: requestedClient)?.toString(), code=result.code?.toString()
            if(previous!=null && issued!=previous.clientId)throw new SubscriptionAuthException('client_registration_mismatch')
            if (!(issued ==~ /^[A-Za-z0-9._-]{3,256}$/) || issued=='dynamic_agent_client' || !code) throw new SubscriptionAuthException('registration_incomplete')
            OAuthHttpResponse response=transport.postForm(TOKEN,[grant_type:'authorization_code',client_id:issued,
                code:code,code_verifier:verifier,redirect_uri:redirect.toString(),resource:RESOURCE],Duration.ofSeconds(20))
            if (response.status != 200) throw new SubscriptionAuthException('token_exchange_rejected')
            Map token=parseJson(response.body)
            Set<String> scopes=(token.scope?.toString()?.split(/\s+/)?.findAll { it } ?: []) as Set
            if (!scopes.containsAll(OpenAiSiwcAdapter.SCOPES)) throw new SubscriptionAuthException('plan_permission_not_granted')
            String subject=validateIdToken(token.id_token?.toString(),issued,nonce)
            if(previous?.subject && previous.subject!=subject)throw new SubscriptionAuthException('account_identity_mismatch')
            long expires
            try { expires=Long.parseLong(token.expires_in?.toString()) } catch(Exception ignored) { throw new SubscriptionAuthException('token_response_invalid') }
            if(expires<1L || expires>86_400L)throw new SubscriptionAuthException('token_response_invalid')
            SubscriptionCredential record
            try {
                record=new SubscriptionCredential(provider:'codex',adapterRevision:OpenAiSiwcAdapter.REVISION,
                    generation:(previous?.generation ?: 0)+1,issuer:'https://auth.openai.com',clientId:issued,hostId:hostId,subject:subject,
                    accessToken:token.access_token,refreshToken:token.refresh_token,idToken:token.id_token,
                    expiresAt:Instant.now().plusSeconds(expires),earliestRefreshAt:token.earliest_refresh_at,scopes:scopes)
                new OpenAiSiwcAdapter().validate(record)
            } catch(SubscriptionAuthException e) { throw e }
            catch(Exception ignored) { throw new SubscriptionAuthException('token_response_invalid') }
            store.save(record)
        } finally { server.stop(0) }
    }

    private String validateIdToken(String raw,String clientId,String nonce) {
        try {
            SignedJWT jwt=SignedJWT.parse(raw)
            if (!(jwt.getHeader().getAlgorithm() in [JWSAlgorithm.RS256,JWSAlgorithm.RS384,JWSAlgorithm.RS512])) throw new IllegalArgumentException()
            Map discovery=parseJson(transport.get(DISCOVERY,[:],Duration.ofSeconds(20)).body)
            URI jwks=URI.create(discovery.jwks_uri?.toString())
            if (jwks.scheme!='https' || jwks.host!='auth.openai.com' || !(jwks.port in [-1,443])) throw new IllegalArgumentException()
            JWKSet keys=JWKSet.parse(new String(transport.get(jwks,[:],Duration.ofSeconds(20)).body,StandardCharsets.UTF_8))
            RSAKey key=keys.getKeyByKeyId(jwt.getHeader().getKeyID()) as RSAKey
            if (key==null || !jwt.verify(new RSASSAVerifier(key.toRSAPublicKey()))) throw new IllegalArgumentException()
            def claims=jwt.getJWTClaimsSet()
            if (claims.issuer!='https://auth.openai.com' || !claims.audience?.contains(clientId) ||
                claims.expirationTime==null || !claims.expirationTime.toInstant().isAfter(Instant.now()) ||
                claims.getStringClaim('nonce')!=nonce || !claims.subject) {
                throw new IllegalArgumentException()
            }
            claims.subject
        } catch (Exception ignored) { throw new SubscriptionAuthException('id_token_invalid') }
    }

    private String hostIdUnlocked() {
        Path file=store.authRoot.resolve('host-id')
        if (Files.exists(file,LinkOption.NOFOLLOW_LINKS)) {
            validateHostIdFile(file)
            String existing=Files.readString(file,StandardCharsets.US_ASCII).trim()
            if (!(existing ==~ /^urn:uuid:[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/)) {
                throw new SubscriptionAuthException('host_id_unsafe')
            }
            return existing
        }
        String value='urn:uuid:'+UUID.randomUUID().toString()
        Path temp=Files.createTempFile(store.authRoot,'.host-id-','.tmp')
        try {
            if (Files.getFileStore(temp).supportsFileAttributeView('posix')) {
                Files.setPosixFilePermissions(temp,PosixFilePermissions.fromString('rw-------'))
            }
            byte[] bytes=value.getBytes(StandardCharsets.US_ASCII)
            try(FileChannel channel=FileChannel.open(temp,StandardOpenOption.WRITE,StandardOpenOption.TRUNCATE_EXISTING)) {
                channel.write(ByteBuffer.wrap(bytes));channel.force(true)
            }
            try { Files.move(temp,file,StandardCopyOption.ATOMIC_MOVE) }
            catch(AtomicMoveNotSupportedException ignored) { Files.move(temp,file) }
            try(FileChannel directory=FileChannel.open(store.authRoot,StandardOpenOption.READ)){directory.force(true)}
        } catch(Exception ignored) {
            throw new SubscriptionAuthException('host_id_write_failed')
        } finally {
            Files.deleteIfExists(temp)
        }
        value
    }

    private void validateHostIdFile(Path file) {
        if(Files.isSymbolicLink(file)||!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.size(file)>128) {
            throw new SubscriptionAuthException('host_id_unsafe')
        }
        if(Files.getFileStore(file).supportsFileAttributeView('posix')) {
            if(Files.getPosixFilePermissions(file,LinkOption.NOFOLLOW_LINKS)!=PosixFilePermissions.fromString('rw-------') ||
                Files.getOwner(file,LinkOption.NOFOLLOW_LINKS)!=Files.getOwner(store.authRoot,LinkOption.NOFOLLOW_LINKS)) {
                throw new SubscriptionAuthException('host_id_unsafe')
            }
            try {
                if((Files.getAttribute(file,'unix:nlink',LinkOption.NOFOLLOW_LINKS) as Number).longValue()!=1L) {
                    throw new SubscriptionAuthException('host_id_unsafe')
                }
            } catch(UnsupportedOperationException ignored) {}
        }
    }
    private String randomValue(int bytes) { byte[] value=new byte[bytes];random.nextBytes(value);Base64.urlEncoder.withoutPadding().encodeToString(value) }
    private static String encode(Map<String,String> values) { values.collect { k,v -> enc(k)+'='+enc(v) }.join('&') }
    private static String enc(String value) { URLEncoder.encode(value,StandardCharsets.UTF_8) }
    private static Map parseQuery(String raw) {
        Map out=[:];(raw?:'').split('&').findAll{it}.each { pair ->
            def parts=pair.split('=',2);out[URLDecoder.decode(parts[0],StandardCharsets.UTF_8)]=URLDecoder.decode(parts.size()>1?parts[1]:'',StandardCharsets.UTF_8)
        };out
    }
    private static Map parseJson(byte[] bytes) {
        try { StrictJson.parseObject(bytes) }
        catch(Exception ignored) { throw new SubscriptionAuthException('provider_response_invalid') }
    }
}
