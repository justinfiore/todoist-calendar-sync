package todoistcaldavsync.planner.ai

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import groovy.json.JsonOutput
import spock.lang.Specification

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

class SubscriptionCredentialAndOAuthSpec extends Specification {
    File root
    Instant now=Instant.parse('2026-10-04T12:00:00Z')

    def setup() { root=File.createTempDir('subscription-auth-','') }
    def cleanup() { root?.deleteDir() }

    private SubscriptionCredential credential(Map extra=[:]) {
        new SubscriptionCredential([provider:'codex',adapterRevision:OpenAiSiwcAdapter.REVISION,generation:1,
            issuer:'https://auth.openai.com',clientId:'oaiapp_fixture',hostId:'urn:uuid:11111111-1111-4111-8111-111111111111',
            subject:'opaque-subject',accessToken:'access-old',refreshToken:'refresh-old',idToken:'id-old',
            expiresAt:now.minusSeconds(1),scopes:OpenAiSiwcAdapter.SCOPES]+extra)
    }

    def "private store round trips versioned credentials with owner-only files and provider isolation"() {
        given:
        def codex=new SubscriptionCredentialStore(root.toPath(),'codex')
        def grok=new SubscriptionCredentialStore(root.toPath(),'grok')

        when:
        codex.save(credential())

        then:
        codex.load().get().accessToken=='access-old'
        codex.load().get().toString()=='SubscriptionCredential(codex,generation=1)'
        grok.load().empty
        if (Files.getFileStore(root.toPath()).supportsFileAttributeView('posix')) {
            assert Files.getPosixFilePermissions(root.toPath().resolve('providers/codex/credential.json')) ==
                PosixFilePermissions.fromString('rw-------')
        }

        when: 'a new process view starts with a stale pre-replacement temporary file'
        Files.writeString(root.toPath().resolve('providers/codex/.credential-stale.tmp'),'{partial')
        def restarted=new SubscriptionCredentialStore(root.toPath(),'codex')

        then:
        restarted.load().get().refreshToken=='refresh-old'

        when: 'replacement fails before touching the prior complete record'
        Files.delete(root.toPath().resolve('providers/codex/.credential-stale.tmp'))
        def failing=new SubscriptionCredentialStore(root.toPath(),'codex',{Path source,Path target->
            throw new IOException('injected move failure')
        })
        failing.save(credential(accessToken:'access-new',refreshToken:'refresh-new'))

        then:
        def moveError=thrown(SubscriptionAuthException)
        moveError.code=='credential_write_failed'
        restarted.load().get().refreshToken=='refresh-old'
        !root.toPath().resolve('providers/codex').toFile().listFiles().any{it.name.startsWith('.credential-') && it.name.endsWith('.tmp')}
    }

    def "store rejects symlinks unsafe modes hard links malformed and oversized records without exposing content"() {
        given:
        def store=new SubscriptionCredentialStore(root.toPath(),'codex');store.save(credential())
        def file=root.toPath().resolve('providers/codex/credential.json')

        when: Files.setPosixFilePermissions(file,PosixFilePermissions.fromString('rw-r--r--'));store.load()
        then:
        def permissionsError=thrown(SubscriptionAuthException)
        permissionsError.code=='credential_permissions_unsafe'

        when:
        Files.setPosixFilePermissions(file,PosixFilePermissions.fromString('rw-------'))
        def hard=root.toPath().resolve('providers/codex/alias');Files.createLink(hard,file);store.load()
        then:
        def hardlinkError=thrown(SubscriptionAuthException)
        hardlinkError.code=='credential_hardlink_unsafe'

        when:
        Files.delete(hard);Files.writeString(file,'{broken',StandardCharsets.UTF_8);store.load()
        then:
        def malformedError=thrown(SubscriptionAuthException)
        malformedError.code=='credential_malformed'

        when:
        Files.write(file,new byte[65_537]);store.load()
        then:
        def oversizedError=thrown(SubscriptionAuthException)
        oversizedError.code=='credential_too_large'

        when:
        String duplicate='{"version":1,"version":1,"provider":"codex"}'
        Files.writeString(file,duplicate,StandardCharsets.UTF_8)
        store.load()

        then:
        def duplicateError=thrown(SubscriptionAuthException)
        duplicateError.code=='credential_malformed'
    }

    def "store rejects an unsafe auth root and a lock-file symlink"() {
        given:
        if (!Files.getFileStore(root.toPath()).supportsFileAttributeView('posix')) return
        def store=new SubscriptionCredentialStore(root.toPath(),'codex')

        when:
        Files.setPosixFilePermissions(root.toPath(),PosixFilePermissions.fromString('rwxr-xr-x'))
        store.load()

        then:
        def rootError=thrown(SubscriptionAuthException)
        rootError.code=='credential_permissions_unsafe'

        when:
        Files.setPosixFilePermissions(root.toPath(),PosixFilePermissions.fromString('rwx------'))
        store.save(credential())
        def lock=root.toPath().resolve('providers/codex/credential.lock')
        Files.delete(lock)
        Files.createSymbolicLink(lock,root.toPath().resolve('providers/codex/credential.json'))
        store.load()

        then:
        def lockError=thrown(SubscriptionAuthException)
        lockError.code=='credential_path_unsafe'
    }

    def "authentication exceptions reduce arbitrary provider content to a safe class"() {
        given:
        String sensitive='access-secret@example.test /home/alice/.codex/auth.json'

        when:
        def error=new SubscriptionAuthException(sensitive)
        String rendered=error.toString()+' '+error.message

        then:
        error.code=='authentication_failed'
        !rendered.contains('access-secret')
        !rendered.contains('example.test')
        !rendered.contains('/home/alice')
    }

    def "refresh is serialized reread under lock and persists one rotating token before either caller returns"() {
        given:
        def store=new SubscriptionCredentialStore(root.toPath(),'codex');store.save(credential())
        def calls=new AtomicInteger();def entered=new CountDownLatch(1);def release=new CountDownLatch(1)
        SubscriptionOAuthTransport transport=[
            postForm:{ uri,form,timeout ->
                calls.incrementAndGet();assert form.refresh_token=='refresh-old';entered.countDown();release.await()
                new OAuthHttpResponse(200,JsonOutput.toJson([access_token:'access-new',refresh_token:'refresh-new',
                    expires_in:3600,scope:OpenAiSiwcAdapter.SCOPES.join(' ')]).bytes)
            },
            get:{ uri,headers,timeout -> throw new AssertionError('unexpected GET') }
        ] as SubscriptionOAuthTransport
        def service=new SubscriptionCredentialService(store,new OpenAiSiwcAdapter(),transport,{now},Duration.ofMinutes(2))
        List<String> values=Collections.synchronizedList([]);List<Throwable> errors=Collections.synchronizedList([])

        when:
        Thread a=Thread.start { try { values << service.accessToken() } catch(Throwable e){errors<<e} }
        assert entered.await(5,java.util.concurrent.TimeUnit.SECONDS)
        Thread b=Thread.start { try { values << service.accessToken() } catch(Throwable e){errors<<e} }
        release.countDown();a.join();b.join()

        then:
        errors.empty
        calls.get()==1
        values==['access-new','access-new']
        store.load().get().refreshToken=='refresh-new'
        store.load().get().generation==2
    }

    def "local status performs zero network and terminal refresh errors require reauthentication"() {
        given:
        def store=new SubscriptionCredentialStore(root.toPath(),'codex');store.save(credential(expiresAt:now.plusSeconds(600)))
        def calls=new AtomicInteger()
        SubscriptionOAuthTransport noNetwork=[postForm:{a,b,c->calls.incrementAndGet();throw new AssertionError()},
            get:{a,b,c->calls.incrementAndGet();throw new AssertionError()}] as SubscriptionOAuthTransport
        def local=new SubscriptionCredentialService(store,new OpenAiSiwcAdapter(),noNetwork,{now})

        expect:
        local.status(false).state=='ready'
        calls.get()==0
        !['access-old','refresh-old','id-old','opaque-subject','oaiapp_fixture',root.path].any {
            JsonOutput.toJson(local.status(false)).contains(it)
        }

        when:
        store.save(credential(expiresAt:now.plusSeconds(60)))

        then:
        local.status(false).state=='expiring'
        calls.get()==0

        when:
        store.save(credential())
        SubscriptionOAuthTransport rejected=[postForm:{a,b,c->new OAuthHttpResponse(400,'{"error":"invalid_grant"}'.bytes)},
            get:{a,b,c->throw new AssertionError()}] as SubscriptionOAuthTransport
        new SubscriptionCredentialService(store,new OpenAiSiwcAdapter(),rejected,{now}).accessToken()

        then:
        def error=thrown(SubscriptionAuthException)
        error.code=='reauthentication_required' && error.reauthenticationRequired
        store.load().get().refreshToken=='refresh-old'
    }

    def "remote status probes the public model catalog with bearer and logout discovers a pinned revocation endpoint"() {
        given:
        def store=new SubscriptionCredentialStore(root.toPath(),'codex');store.save(credential(expiresAt:now.plusSeconds(600)))
        List calls=[]
        SubscriptionOAuthTransport transport=[postForm:{uri,form,timeout->calls<<[uri,form];new OAuthHttpResponse(200,new byte[0])},
            get:{uri,headers,timeout->calls<<[uri,headers]
                if(uri.path=='/v1/models') return new OAuthHttpResponse(200,'{"data":[]}'.bytes)
                new OAuthHttpResponse(200,'{"revocation_endpoint":"https://auth.openai.com/oauth/revoke"}'.bytes)
            }] as SubscriptionOAuthTransport
        def service=new SubscriptionCredentialService(store,new OpenAiSiwcAdapter(),transport,{now})

        expect:
        service.status(true).remoteEntitlement=='available'
        calls[0][0].toString()=='https://api.openai.com/v1/models'
        calls[0][1].Authorization=='Bearer access-old'

        when: def outcome=service.logout()
        then:
        outcome=='revoked_and_removed'
        store.load().empty
        calls.last()[0].toString()=='https://auth.openai.com/oauth/revoke'
        calls.last()[1].keySet()==['token','token_type_hint','client_id'] as Set
    }

    def "xAI adapter is bounded to the source-visible registration and exact requested scopes"() {
        given: def adapter=new XaiDeviceOAuthAdapter()
        expect:
        adapter.refreshEndpoint(null).toString()=='https://auth.x.ai/oauth2/token'
        adapter.refreshForm(new SubscriptionCredential(provider:'grok',adapterRevision:adapter.revision(),generation:1,
            issuer:adapter.issuer(),clientId:XaiDeviceOAuthAdapter.CLIENT_ID,accessToken:'a',refreshToken:'r',
            expiresAt:now.plusSeconds(60),scopes:XaiDeviceOAuthAdapter.SCOPES)).keySet()==['grant_type','client_id','refresh_token'] as Set
    }

    def "xAI RFC 8628 flow handles pending and slow-down then stores a normalized rotating credential"() {
        given:
        def store=new SubscriptionCredentialStore(root.toPath(),'grok');def sleeps=[];def polls=new AtomicInteger()
        SubscriptionOAuthTransport transport=[
            get:{uri,headers,timeout->new OAuthHttpResponse(200,'{"token_endpoint":"https://auth.x.ai/oauth2/token"}'.bytes)},
            postForm:{uri,form,timeout->
                if(uri.path=='/oauth2/device/code') return new OAuthHttpResponse(200,JsonOutput.toJson([
                    device_code:'device-secret',user_code:'ABCD-EFGH',verification_uri:'https://auth.x.ai/device',
                    interval:1,expires_in:600]).bytes)
                int call=polls.incrementAndGet()
                if(call==1)return new OAuthHttpResponse(400,'{"error":"authorization_pending"}'.bytes)
                if(call==2)return new OAuthHttpResponse(400,'{"error":"slow_down"}'.bytes)
                new OAuthHttpResponse(200,JsonOutput.toJson([access_token:'x-access',refresh_token:'x-refresh',
                    id_token:'x-id',expires_in:3600,scope:XaiDeviceOAuthAdapter.SCOPES.join(' ')]).bytes)
            }] as SubscriptionOAuthTransport
        def out=new StringBuilder()

        when:
        new XaiDeviceLogin(store,transport,{long millis->sleeps<<millis},{now}).login(out)

        then:
        sleeps==[1000L,1000L,6000L]
        out.toString().contains('https://auth.x.ai/device') && out.toString().contains('ABCD-EFGH')
        store.load().get().accessToken=='x-access'
        store.load().get().adapterRevision==XaiDeviceOAuthAdapter.REVISION
    }

    def "xAI device flow pins discovered hosts and classifies denial without persisting"() {
        given:
        def store=new SubscriptionCredentialStore(root.toPath(),'grok')
        SubscriptionOAuthTransport hostile=[get:{a,b,c->new OAuthHttpResponse(200,
            '{"token_endpoint":"https://attacker.example/token"}'.bytes)},postForm:{a,b,c->throw new AssertionError()}] as SubscriptionOAuthTransport

        when: new XaiDeviceLogin(store,hostile,{},{now}).login(new StringBuilder())
        then:
        def error=thrown(SubscriptionAuthException)
        error.code=='provider_endpoint_unsafe'
        store.load().empty

        when:
        SubscriptionOAuthTransport hostileVerification=[get:{a,b,c->new OAuthHttpResponse(200,
            '{"token_endpoint":"https://auth.x.ai/oauth2/token"}'.bytes)},postForm:{uri,form,timeout->
            new OAuthHttpResponse(200,'{"device_code":"secret","user_code":"CODE","verification_uri":"https://attacker.example/device","expires_in":60}'.bytes)
        }] as SubscriptionOAuthTransport
        new XaiDeviceLogin(store,hostileVerification,{},{now}).login(new StringBuilder())

        then:
        def verificationError=thrown(SubscriptionAuthException)
        verificationError.code=='provider_endpoint_unsafe'
        store.load().empty
    }

    def "xAI device cancellation is classified and preserves the existing credential"() {
        given:
        def store=new SubscriptionCredentialStore(root.toPath(),'grok')
        def existing=new SubscriptionCredential(provider:'grok',adapterRevision:XaiDeviceOAuthAdapter.REVISION,generation:1,
            issuer:'https://auth.x.ai',clientId:XaiDeviceOAuthAdapter.CLIENT_ID,accessToken:'old-access',
            refreshToken:'old-refresh',expiresAt:now.plusSeconds(600),scopes:XaiDeviceOAuthAdapter.SCOPES)
        store.save(existing)
        SubscriptionOAuthTransport transport=[get:{a,b,c->new OAuthHttpResponse(200,
            '{"token_endpoint":"https://auth.x.ai/oauth2/token"}'.bytes)},postForm:{uri,form,timeout->
            new OAuthHttpResponse(200,'{"device_code":"secret","user_code":"CODE","verification_uri":"https://auth.x.ai/device","expires_in":60}'.bytes)
        }] as SubscriptionOAuthTransport

        when:
        new XaiDeviceLogin(store,transport,{throw new InterruptedException()},{now}).login(new StringBuilder())

        then:
        def error=thrown(SubscriptionAuthException)
        error.code=='login_cancelled'
        Thread.interrupted()
        store.load().get().refreshToken=='old-refresh'

        cleanup:
        Thread.interrupted()
    }

    def "OpenAI browser login uses dynamic registration PKCE loopback and validates signed identity before replacement"() {
        given:
        def store=new SubscriptionCredentialStore(root.toPath(),'codex')
        def key=new RSAKeyGenerator(2048).keyID('fixture-key').generate();Map authorization
        String callbackClient='oaiapp_fixture', fixtureSubject='opaque-subject'
        String fixtureIssuer='https://auth.openai.com', fixtureAudience='oaiapp_fixture', fixtureNonce=null
        Date fixtureExpiry=Date.from(Instant.now().plusSeconds(3600))
        Closure opener={URI uri->
            authorization=query(uri.rawQuery)
            assert uri.scheme=='https' && uri.host=='auth.openai.com' && uri.path=='/api/accounts/authorize'
            assert authorization.client_id in ['dynamic_agent_client','oaiapp_fixture']
            assert authorization.redirect_uri.startsWith('http://127.0.0.1:')
            assert authorization.code_challenge_method=='S256'
            URI callback=URI.create(authorization.redirect_uri+'?code=fixture-code&state='+enc(authorization.state)+
                '&client_id='+enc(callbackClient))
            HttpClient.newHttpClient().send(HttpRequest.newBuilder(callback).GET().build(),HttpResponse.BodyHandlers.discarding())
        }
        SubscriptionOAuthTransport transport=[
            postForm:{uri,form,timeout->
                assert uri.toString()=='https://auth.openai.com/api/accounts/oauth/token'
                assert form.grant_type=='authorization_code' && form.code=='fixture-code' && form.code_verifier
                def claimsBuilder=new JWTClaimsSet.Builder().issuer(fixtureIssuer).audience(fixtureAudience)
                    .subject(fixtureSubject).claim('nonce',fixtureNonce ?: authorization.nonce)
                if(fixtureExpiry!=null)claimsBuilder.expirationTime(fixtureExpiry)
                def claims=claimsBuilder.build()
                def jwt=new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID('fixture-key').build(),claims)
                jwt.sign(new RSASSASigner(key))
                new OAuthHttpResponse(200,JsonOutput.toJson([access_token:'fixture-access-secret',refresh_token:'fixture-refresh-secret',
                    id_token:jwt.serialize(),expires_in:3600,scope:OpenAiSiwcAdapter.SCOPES.join(' ')]).bytes)
            },
            get:{uri,headers,timeout->
                if(uri.path.endsWith('openid-configuration'))return new OAuthHttpResponse(200,
                    '{"jwks_uri":"https://auth.openai.com/.well-known/jwks.json"}'.bytes)
                new OAuthHttpResponse(200,new JWKSet(key.toPublicJWK()).toString().bytes)
            }] as SubscriptionOAuthTransport
        def out=new StringBuilder()

        when:
        new OpenAiSiwcLogin(store,transport,Duration.ofSeconds(5),opener).login(out)

        then:
        store.load().get().subject=='opaque-subject'
        store.load().get().clientId=='oaiapp_fixture'
        store.load().get().hostId.startsWith('urn:uuid:')
        out.toString().contains('https://auth.openai.com/api/accounts/authorize?')
        !out.toString().contains('fixture-access-secret') && !out.toString().contains('fixture-refresh-secret')

        when: 'the provider attempts to replace the issued client registration'
        String original=Files.readString(root.toPath().resolve('providers/codex/credential.json'))
        callbackClient='oaiapp_replacement'
        new OpenAiSiwcLogin(store,transport,Duration.ofSeconds(5),opener).login(new StringBuilder())

        then:
        def clientError=thrown(SubscriptionAuthException)
        clientError.code=='client_registration_mismatch'
        Files.readString(root.toPath().resolve('providers/codex/credential.json'))==original

        when: 'a repeat login returns a different account identity'
        callbackClient='oaiapp_fixture';fixtureSubject='different-subject'
        new OpenAiSiwcLogin(store,transport,Duration.ofSeconds(5),opener).login(new StringBuilder())

        then:
        def identityError=thrown(SubscriptionAuthException)
        identityError.code=='account_identity_mismatch'
        Files.readString(root.toPath().resolve('providers/codex/credential.json'))==original

        when: 'a repeat login returns a signed token without the required expiry'
        fixtureSubject='opaque-subject';fixtureExpiry=null
        new OpenAiSiwcLogin(store,transport,Duration.ofSeconds(5),opener).login(new StringBuilder())

        then:
        def expiryError=thrown(SubscriptionAuthException)
        expiryError.code=='id_token_invalid'
        Files.readString(root.toPath().resolve('providers/codex/credential.json'))==original

        when: 'a repeat login times out before receiving a callback'
        new OpenAiSiwcLogin(store,transport,Duration.ZERO,{URI ignored->}).login(new StringBuilder())

        then:
        def timeoutError=thrown(SubscriptionAuthException)
        timeoutError.code=='login_timeout'
        Files.readString(root.toPath().resolve('providers/codex/credential.json'))==original

        when: 'signed tokens drift across issuer audience or nonce bindings'
        fixtureExpiry=Date.from(Instant.now().plusSeconds(3600))
        [[issuer:'https://attacker.example',audience:'oaiapp_fixture',nonce:null],
         [issuer:'https://auth.openai.com',audience:'other-client',nonce:null],
         [issuer:'https://auth.openai.com',audience:'oaiapp_fixture',nonce:'wrong-nonce']].each { variant ->
            fixtureIssuer=variant.issuer;fixtureAudience=variant.audience;fixtureNonce=variant.nonce
            try {
                new OpenAiSiwcLogin(store,transport,Duration.ofSeconds(5),opener).login(new StringBuilder())
                assert false:'hostile signed token was accepted'
            } catch(SubscriptionAuthException expected) {
                assert expected.code=='id_token_invalid'
            }
            assert Files.readString(root.toPath().resolve('providers/codex/credential.json'))==original
        }

        then:
        noExceptionThrown()
    }

    private static Map query(String raw) {
        Map result=[:];raw.split('&').each{pair->def p=pair.split('=',2);result[URLDecoder.decode(p[0],StandardCharsets.UTF_8)]=URLDecoder.decode(p[1],StandardCharsets.UTF_8)};result
    }
    private static String enc(String value){URLEncoder.encode(value,StandardCharsets.UTF_8)}
}
