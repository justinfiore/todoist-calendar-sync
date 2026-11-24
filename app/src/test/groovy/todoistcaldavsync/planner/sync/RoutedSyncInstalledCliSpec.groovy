package todoistcaldavsync.planner.sync

import com.github.tomakehurst.wiremock.WireMockServer
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Path
import java.security.KeyStore
import java.util.concurrent.TimeUnit

import static com.github.tomakehurst.wiremock.client.WireMock.*
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options

/** Installed launcher and real HTTPS Todoist/CalDAV adapters, isolated environment and fixture-only trust. */
class RoutedSyncInstalledCliSpec extends Specification {
    @TempDir Path directory
    WireMockServer server

    def setup() {
        Path keyStore = directory.resolve('server.jks')
        Process keytool = new ProcessBuilder(System.getProperty('java.home') + '/bin/keytool',
            '-genkeypair', '-alias', 'localhost', '-keyalg', 'RSA', '-storetype', 'JKS',
            '-keystore', keyStore.toString(), '-storepass', 'fixture-only', '-keypass', 'fixture-only',
            '-dname', 'CN=localhost', '-ext', 'SAN=dns:localhost,ip:127.0.0.1', '-validity', '2')
            .redirectErrorStream(true).start()
        String keytoolOutput = keytool.inputStream.text
        assert keytool.waitFor() == 0: keytoolOutput
        server = new WireMockServer(options().dynamicPort().dynamicHttpsPort()
            .keystorePath(keyStore.toString()).keystorePassword('fixture-only').keyManagerPassword('fixture-only'))
        server.start()
        server.stubFor(get(urlPathEqualTo('/todoist/tasks')).willReturn(okJson(JsonOutput.toJson([
            results: [[id: 'manual', content: 'Keep my late schedule', labels: ['work', 'manual'], project_id: 'p',
                due: [date: '2026-11-24T23:10:00Z'], duration: [amount: 30, unit: 'minute'],
                updated_at: '2026-11-24T10:00:00Z', completed_count: 0]], next_cursor: null]))))
        server.stubFor(get(urlPathEqualTo('/todoist/projects')).willReturn(okJson('''{"results":[{"id":"p","name":"Work"}],"next_cursor":null}''')))
        server.stubFor(get(urlPathEqualTo('/todoist/user')).willReturn(okJson('''{"id":"fixture-account","email":"fixture@example.test"}''')))
        server.stubFor(post(urlPathEqualTo('/todoist/sync')).withRequestBody(containing('resource_types='))
            .willReturn(okJson('''{"sync_token":"fixture-items","full_sync_date_utc":"2026-11-24T00:00:00Z","items":[]}''')))
        server.stubFor(request('PROPFIND', urlMatching('/(work|family)/')).willReturn(aResponse().withStatus(207).withBody('''
<d:multistatus xmlns:d="DAV:"><d:response><d:propstat><d:prop><d:supported-report-set>
<d:supported-report><d:report><d:sync-collection/></d:report></d:supported-report>
</d:supported-report-set></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>''')))
        server.stubFor(request('REPORT', urlMatching('/(work|family)/')).willReturn(aResponse().withStatus(207).withBody(
            '<d:multistatus xmlns:d="DAV:"><d:sync-token>fixture-calendar</d:sync-token></d:multistatus>')))

        // Only public certificates enter the child JVM's trust store; no machine/account credentials are inherited.
        KeyStore fixture = KeyStore.getInstance('JKS')
        keyStore.toFile().withInputStream { fixture.load(it, 'fixture-only'.toCharArray()) }
        KeyStore trust = KeyStore.getInstance('JKS')
        trust.load(null, null)
        fixture.aliases().each { String alias -> trust.setCertificateEntry(alias, fixture.getCertificate(alias)) }
        directory.resolve('trust.jks').toFile().withOutputStream { trust.store(it, 'fixture-only'.toCharArray()) }
    }

    def cleanup() { server?.stop() }

    private Map root(boolean enabled) {
        String base = 'https://localhost:' + server.httpsPort()
        [planner: [mode: 'preview', timezone: 'UTC',
            routing: [mode: 'legacy_rules', labelsToInclude: ['work'], rules: [
                [rule: 'family', calendarName: 'Family'], [rule: 'work', calendarName: 'Work']]],
            sync: [enabled: enabled, interval: 'PT1M'], calendarWriteback: [enabled: true],
            availability: [working_windows: [weekday: ['09:00-17:00']]],
            integration: [todoist: [base_url: base + '/todoist', token_env: 'FIXTURE_TOKEN'],
                calendar: [provider: 'caldav'], caldav: [calendars: ['Work', 'Family'].collect {
                    [name: it, url: base + '/' + it.toLowerCase() + '/', managed_destination: true, auth: [type: 'none']]
                }], state: [plans_dir: directory.resolve('plans').toString(), applications_dir: directory.resolve('applications').toString(),
                    decisions_dir: directory.resolve('decisions').toString(), deliveries_dir: directory.resolve('deliveries').toString()]]]]
    }

    private Map run(String operation, boolean enabled = true) {
        File config = directory.resolve('config.json').toFile()
        config.text = JsonOutput.toJson(root(enabled))
        File log = directory.resolve('logging.groovy').toFile()
        log.text = 'log4j.rootLogger="OFF"\n'
        File launcher = new File('build/install/todoist-caldav-sync/bin/todoist-caldav-sync').absoluteFile
        assert launcher.isFile()
        def builder = new ProcessBuilder([launcher.path, '-f', config.path, '-l', log.path, '--operation', operation,
            '--range-start', '2026-11-24T00:00:00Z', '--range-end', '2026-11-25T00:00:00Z', '--format', 'json'])
        builder.environment().clear()
        builder.environment().putAll([HOME: directory.toString(), PATH: '/usr/local/bin:/usr/bin:/bin',
            JAVA_HOME: System.getProperty('java.home'), FIXTURE_TOKEN: 'fixture-only',
            JAVA_OPTS: '-Djavax.net.ssl.trustStore=' + directory.resolve('trust.jks') + ' -Djavax.net.ssl.trustStorePassword=fixture-only'])
        Path stdout = directory.resolve(operation + '.out'), stderr = directory.resolve(operation + '.err')
        builder.redirectOutput(stdout.toFile()).redirectError(stderr.toFile())
        Process process = builder.start()
        assert process.waitFor(45, TimeUnit.SECONDS): 'installed CLI did not terminate'
        [code: process.exitValue(), output: stdout.toFile().text, error: stderr.toFile().text]
    }

    def 'installed #operation is read-only even with forward and reverse sync configured; disabled sync refuses before HTTP'() {
        when:
        Map result = run(operation)

        then:
        result.code == 0
        result.error.isEmpty()
        Map output = new JsonSlurper().parseText(result.output) as Map
        if (operation == 'planner-sync-preview') {
            assert output.results.any { it.status == 'preview_create' && it.event.start == '2026-11-24T23:10:00Z' }
            assert output.authorities == [forward: false, reverse: false, lifecycle: false, planning: false]
        }
        if (operation == 'planner-migrate') {
            assert output.stage == 'INVENTORIED'
            assert output.candidates == 0
            assert directory.resolve('applications/migration/state.json').toFile().isFile()
        }
        server.allServeEvents.every { it.request.method.value() in ['GET', 'REPORT', 'PROPFIND'] ||
            it.request.url == '/todoist/sync' && it.request.method.value() == 'POST' && !it.request.bodyAsString.contains('commands=') }
        !directory.resolve('applications/sync/todoist-items-sync.json').toFile().exists()
        !directory.resolve('applications/recurrence').toFile().exists()

        when:
        int reads = server.allServeEvents.size()
        Map refused = run('planner-sync', false)

        then:
        refused.code == 2
        refused.error.contains('planner.sync.enabled')
        server.allServeEvents.size() == reads

        where:
        operation << ['planner-sync-preview', 'capacity', 'preview', 'planner-migrate']
    }
}
