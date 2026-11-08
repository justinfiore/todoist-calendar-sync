package todoistcaldavsync

import com.github.tomakehurst.wiremock.WireMockServer
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.time.Instant

import static com.github.tomakehurst.wiremock.client.WireMock.*
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options

class TodoistTaskExporterSpec extends Specification {
    @TempDir Path temp
    WireMockServer server = new WireMockServer(options().dynamicPort())

    def setup() { server.start() }
    def cleanup() { server.stop() }

    Path config(String auth = 'todoist:\n  accessToken: legacy-secret', String extra = '') {
        Path path = temp.resolve('config.yaml')
        Files.writeString(path, """${auth}
planner:
  integration:
    todoist:
      base_url: ${server.baseUrl()}/api/v1
${extra}
""")
        path
    }

    void rest(Object body) {
        server.stubFor(get(urlPathEqualTo('/api/v1/tasks')).willReturn(okJson(JsonOutput.toJson(body))))
    }

    void sync(List items = []) {
        server.stubFor(post(urlEqualTo('/api/v1/sync')).willReturn(okJson(JsonOutput.toJson([
            sync_token: 'cursor-private', full_sync_date_utc: '2026-11-08T00:00:00Z', items: items]))))
    }

    def 'preserves paginated asymmetric raw records and separate recurrence inventory with private integrity metadata'() {
        given:
        List records = [
            [id: '1', due: null, deadline: [date: '2026-12-01'], description: 'private\ntext', labels: ['x'], project_id: 'p', priority: 4, unknown_field: [nested: true]],
            [id: '2', due: [date: '2026-11-09', is_recurring: false], deadline: null],
            [id: '3', due: [date: '2026-11-10', datetime: '2026-11-10T09:00:00Z', timezone: 'UTC', is_recurring: true, string: 'every day', lang: 'en']]
        ]
        server.stubFor(get(urlPathEqualTo('/api/v1/tasks')).withQueryParam('cursor', absent())
            .willReturn(okJson(JsonOutput.toJson([results: records.take(2), next_cursor: 'page 2']))))
        server.stubFor(get(urlPathEqualTo('/api/v1/tasks')).withQueryParam('cursor', equalTo('page 2'))
            .willReturn(okJson(JsonOutput.toJson([results: records.drop(2), next_cursor: null]))))
        List items = [[id: '3', completed_count: 12, due: records[2].due, native_extra: 'kept']]
        sync(items)
        Path cfg = config()
        Path output = temp.resolve('backup-20261108T000000Z')

        when:
        Map manifest = TodoistTaskExporter.exportTasks(cfg, output, { 'wrong-ambient-account' }, true)

        then:
        new JsonSlurper().parse(output.resolve('active-tasks.json').toFile()) == records
        new JsonSlurper().parse(output.resolve('sync-items.json').toFile()) == items
        new JsonSlurper().parse(output.resolve('manifest.json').toFile()) == manifest
        manifest.status == 'complete'
        manifest.files['active-tasks.json'].count == 3
        manifest.files['sync-items.json'].count == 1
        Instant.parse(manifest.capture_started_at) <= Instant.parse(manifest.capture_ended_at)
        PosixFilePermissions.toString(Files.getPosixFilePermissions(output)) == 'rwx------'
        manifest.files.every { name, meta ->
            byte[] bytes = Files.readAllBytes(output.resolve(name))
            meta.bytes == bytes.length && meta.sha256 == MessageDigest.getInstance('SHA-256').digest(bytes).encodeHex().toString() &&
                Instant.parse(meta.capture_started_at) <= Instant.parse(meta.capture_ended_at)
        }
        ['active-tasks.json', 'sync-items.json', 'manifest.json'].every {
            PosixFilePermissions.toString(Files.getPosixFilePermissions(output.resolve(it))) == 'rw-------'
        }
        server.allServeEvents.size() == 3
        server.allServeEvents.every { it.request.getHeader('Authorization') == 'Bearer legacy-secret' }
        server.verify(1, postRequestedFor(urlEqualTo('/api/v1/sync'))
            .withRequestBody(equalTo('sync_token=*&resource_types=%5B%22items%22%5D')))
        server.allServeEvents.every { it.request.method.value() == 'GET' || it.request.url == '/api/v1/sync' }

        when: 'rerun cannot overwrite a good snapshot'
        TodoistTaskExporter.exportTasks(cfg, output, { null }, true)

        then:
        thrown(IllegalStateException)
        new JsonSlurper().parse(output.resolve('active-tasks.json').toFile()) == records
        server.allServeEvents.size() == 3
    }

    def 'uses selected or default environment credentials without legacy fallback'() {
        given:
        rest([results: [], next_cursor: null])
        sync()
        Path cfg = config('', explicit ? '      token_env: CHOSEN_TOKEN' : '')
        List names = []

        when:
        TodoistTaskExporter.exportTasks(cfg, temp.resolve('env-backup'), { String name -> names << name; 'env-secret' }, true)

        then:
        names == [explicit ? 'CHOSEN_TOKEN' : 'TODOIST_ACCESS_TOKEN']
        server.allServeEvents.every { it.request.getHeader('Authorization') == 'Bearer env-secret' }

        where:
        explicit << [true, false]
    }

    def 'missing or conflicting explicit credentials fail safely without requests'() {
        given:
        Path cfg = config('todoist:\n  accessToken: legacy-secret', '      token_env: CHOSEN_TOKEN')
        Path output = temp.resolve('bad-auth')

        when:
        TodoistTaskExporter.exportTasks(cfg, output, { secret }, true)

        then:
        def e = thrown(IllegalStateException)
        !e.message.contains('legacy-secret')
        e.cause == null
        !Files.exists(output)
        server.allServeEvents.empty

        where:
        secret << [null, '', 'different-account']
    }

    def 'incomplete reads never publish data or a success marker'() {
        given:
        switch (failure) {
            case 'repeated cursor': rest([results: [[id: 'private-task']], next_cursor: 'again']); break
            case 'page bound': rest([results: [[id: 'private-task']], next_cursor: 'again']); break
            case 'malformed page': server.stubFor(get(urlPathEqualTo('/api/v1/tasks')).willReturn(okJson('{secret-task'))); break
            case 'http failure': server.stubFor(get(urlPathEqualTo('/api/v1/tasks')).willReturn(unauthorized().withBody('secret-provider-body'))); break
            default: rest([results: [[id: 'private-task']], next_cursor: null])
        }
        if (failure == 'sync failure') server.stubFor(post(urlEqualTo('/api/v1/sync')).willReturn(okJson('{}')))
        else sync()
        Path cfg = config('todoist:\n  accessToken: legacy-secret', failure == 'page bound' ? '      max_pages: 1' : '')
        Path output = temp.resolve('failed-backup')

        when:
        TodoistTaskExporter.exportTasks(cfg, output, { null }, true)

        then:
        def e = thrown(IllegalStateException)
        e.cause == null
        !e.message.contains('secret')
        Files.isDirectory(output)
        Files.list(output).withCloseable { it.count() } == 0

        where:
        failure << ['repeated cursor', 'page bound', 'malformed page', 'http failure', 'sync failure']
    }
}
