package todoistcaldavsync.planner.oauth

import com.google.api.client.testing.http.MockHttpTransport
import com.google.api.client.testing.http.MockLowLevelHttpResponse
import groovy.json.JsonOutput
import spock.lang.Specification

import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Instant

class GoogleIdTokenIdentityVerifierSpec extends Specification {
    private static final String CLIENT_ID = 'local-client.apps.example.test'
    private static final String CERTIFICATE = '''-----BEGIN CERTIFICATE-----
MIIDCzCCAfOgAwIBAgIUVnF857fN0b3s0FC+BzgaJg8BpiQwDQYJKoZIhvcNAQEL
BQAwFTETMBEGA1UEAwwKb2F1dGgtdGVzdDAeFw0yNjA4MjEyMTE4MjVaFw0zNjA4
MTgyMTE4MjVaMBUxEzARBgNVBAMMCm9hdXRoLXRlc3QwggEiMA0GCSqGSIb3DQEB
AQUAA4IBDwAwggEKAoIBAQC/Fxj0DGiRLA+VamZ2bbDCJqmDLSRdH8mdja+ApIPc
GF9j+w5zuKux5WryYEQ1KE7v8Y9pprgMnQQ+jc9HH1FtYUZfFZk9bO3pQAgmiYgL
vv1arMeKXacU9CD69S9iCcpwH+D695fWmT7G+45/NN+50E7SSXiVRaacQgXJ3hKu
M2iRWN4KTvMMvgJTeLBYadPUyUnMt+OgpwPJrpIGNY65YiZ+T33afundkkU/cx7R
Mvg8v4uxw0k0NNTUW0h6dCuPDiOX5P0Pkm0dEdVhYtHTlOD9Ea4IBRs9bp6HQ7qo
ZFF09ZMGLRrkB+GSUSRx4VMUOaCIk1biw7zoS7P5gcHRAgMBAAGjUzBRMB0GA1Ud
DgQWBBROzzntghGwoXhtd6pUtPg+IslXpjAfBgNVHSMEGDAWgBROzzntghGwoXht
d6pUtPg+IslXpjAPBgNVHRMBAf8EBTADAQH/MA0GCSqGSIb3DQEBCwUAA4IBAQCh
DcTHEDQzVkbafrE2++8DwRyYutnhE6f+fLHGPSHVMGAzsHBdvKCU4j3xCm09qJOM
fV7+njU0cnL4zYN/aZZu5vod74ZnJ1WnNKWAFMq+pSy+cn7s4R7bf5bqNxOnN9l7
wvP39PN6YxSzwb5SnGbhd8nWZ3PhTt8CeP/Imm9RNi+m+p0HKLRtjT6PX4qtuNI3
7u7/7RdKWU7x+m5h2VRvG0qXJJE3gWfsmB7FtYyxqCpTmZjtMo8IKYTdMXb7OuAy
xjiz+YuHVdMgaVIco2LjCca9gUYUP/vvWYFtc0+qpqCj3BpxIKAse4pP8vHHIRsO
1wcCvKh44aixFO5lCPTC
-----END CERTIFICATE-----'''
    private static final String PRIVATE_KEY = '''-----BEGIN PRIVATE KEY-----
MIIEvQIBADANBgkqhkiG9w0BAQEFAASCBKcwggSjAgEAAoIBAQC/Fxj0DGiRLA+V
amZ2bbDCJqmDLSRdH8mdja+ApIPcGF9j+w5zuKux5WryYEQ1KE7v8Y9pprgMnQQ+
jc9HH1FtYUZfFZk9bO3pQAgmiYgLvv1arMeKXacU9CD69S9iCcpwH+D695fWmT7G
+45/NN+50E7SSXiVRaacQgXJ3hKuM2iRWN4KTvMMvgJTeLBYadPUyUnMt+OgpwPJ
rpIGNY65YiZ+T33afundkkU/cx7RMvg8v4uxw0k0NNTUW0h6dCuPDiOX5P0Pkm0d
EdVhYtHTlOD9Ea4IBRs9bp6HQ7qoZFF09ZMGLRrkB+GSUSRx4VMUOaCIk1biw7zo
S7P5gcHRAgMBAAECggEAJI+Lw63YF+aBOMo5vnDwP8Vb63Aoo/SgA5gHOyq+286B
+cQgGL39g7TsSGFoy27h44CpOsKeNjOYi0tgnC/+yVmyOCEOx7TetCD/LjhkIjMx
kDa8mtmeTSEEal+c6DoNVHSU/A+BKpr5auLYebgpEgkr+4n5Gz5PSVhMeToXAuMY
dcklwfvtUIJyVg7OTur/3LEQQntgsOzpMz31v27JmJrfDSUdQmV1Iu5UcdbCs3Lh
mzsajQaLOgXhWA9UNKoEK2T4bMCyxqdDj9PAitm4BKstp+EPKMnsuJcJJ5ioPWN+
hxv6Ku33TEe0dej+L6nKPUJdERR2dxjcEXB/JrDA/QKBgQDi9rmymxm36Wd3uDR7
x2tP2PH7ZdQ1jUFGG6FuOyVYDmXFFfjgPz1PAcUHT4zwC9af+hsouNQM4jL9vxgF
N5NcvIKjBmxaGhzrvkzPVE7/snycFoI9LeNt8ailhc1uDo/bk7FPcxwkp8UwQUXj
/BK5QMdUrU4r+A19fajIj86YowKBgQDXiXrmMzX69e/kNA8iyheh0XqOVapY9DrX
/k32N0y02mIKT7FGtErc8DAmuGdIzCXAHaIf9qVdQrvOBPoqvczYGn026XFL2w9W
eUN5g2zmWpp/g8BRESbaLlFMeZ2qSlWTKj6fl7fr9gIKHWwa0c2p6bqQOL7g5XVS
DO85QaUe+wKBgQCkU3Kb6EQ0rh8lxQ7q17XQuAhrtoxwwXcTJYo530TofnQcwA3T
frYK8AMRif1HB6s7ZMApObj+IwA8TBE+JcDiEfKbyljE28c4wC2opygTZc1mzb06
QnE59w/d2ASmvJBXsJVeKr+jonzYtUm/CZEc49PucRP6LbzGsSZ1H/m69QKBgEL8
Z0XEBLLKXJP/2fRl+pJAGGbEGP0sal1Wm8Q5y40pke7CdcYTonCn9U8TYIYvbEwY
6ZolfZ9Obi/JPDasZk2Dbgby5lM88bdeWKobPm0ZG4sl109alUiZvIqYAXg7Qf1K
08uly3N6MYgTPNXY/qIgEetgt3IN3jhx7KdOz5KhAoGAMvjjsfLZjuhq6p/wP8sZ
1VS/p7Zz2NSO8He08+BJ/f6rH58Wovh58Qt0mcOTlWNuYddecOrp6fskxfOufbg1
gcHdMtJW2SD8kMuO5HR1NwrRjGbGGS2kYNMGwgkZifGzkF9nnPb7iCvPhS27jRtV
fZ3qXG+pRIL8p9B0xJmd4fA=
-----END PRIVATE KEY-----'''

    def "production verifier accepts a locally signed valid Google identity token"() {
        given:
        def verifier = verifier()

        expect:
        verifier.verify(token([:]), material()) == new GoogleOAuthVerifiedIdentity('subject-123', 'owner@example.test')
    }

    def "production verifier rejects invalid identity token evidence"() {
        given:
        def verifier = verifier()

        when:
        verifier.verify(token(overrides, corruptSignature), material())

        then:
        def error = thrown(GoogleOAuthException)
        error.classification == GoogleOAuthErrorClass.ACCOUNT_MISMATCH
        error.message == 'Google OAuth identity evidence could not be verified'

        where:
        overrides                                                        | corruptSignature
        [aud: 'different-client.apps.example.test']                       | false
        [iss: 'https://attacker.example.test']                            | false
        [:]                                                               | true
        [exp: Instant.now().minusSeconds(3600).epochSecond]               | false
        [email_verified: false]                                           | false
    }

    private static GoogleIdTokenIdentityVerifier verifier() {
        def response = new MockLowLevelHttpResponse()
            .setStatusCode(200)
            .setContentType('application/json')
            .setContent(JsonOutput.toJson(['local-key': CERTIFICATE]))
            .addHeader('Cache-Control', 'max-age=3600')
        new GoogleIdTokenIdentityVerifier(new MockHttpTransport.Builder().setLowLevelHttpResponse(response).build())
    }

    private static GoogleOAuthClientMaterial material() {
        new GoogleOAuthClientMaterial(CLIENT_ID, 'unused', new URI('https://accounts.example.test/auth'),
            new URI('https://accounts.example.test/token'))
    }

    private static String token(Map overrides, boolean corruptSignature = false) {
        long now = Instant.now().epochSecond
        Map claims = [iss: 'https://accounts.google.com', aud: CLIENT_ID, sub: 'subject-123',
                      email: 'owner@example.test', email_verified: true, iat: now - 10, exp: now + 3600]
        claims.putAll(overrides)
        String signingInput = encode(JsonOutput.toJson([alg: 'RS256', typ: 'JWT', kid: 'local-key'])) + '.' +
            encode(JsonOutput.toJson(claims))
        Signature signer = Signature.getInstance('SHA256withRSA')
        signer.initSign(privateKey())
        signer.update(signingInput.getBytes(StandardCharsets.US_ASCII))
        byte[] signed = signer.sign()
        if (corruptSignature) signed[0] ^= 1
        signingInput + '.' + Base64.urlEncoder.withoutPadding().encodeToString(signed)
    }

    private static String encode(String value) {
        Base64.urlEncoder.withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8))
    }

    private static PrivateKey privateKey() {
        String encoded = PRIVATE_KEY.replace('-----BEGIN PRIVATE KEY-----', '')
            .replace('-----END PRIVATE KEY-----', '').replaceAll(/\s/, '')
        KeyFactory.getInstance('RSA').generatePrivate(new PKCS8EncodedKeySpec(Base64.decoder.decode(encoded)))
    }
}
