package todoistcaldavsync.planner.state

import groovy.json.JsonOutput
import spock.lang.Specification
import todoistcaldavsync.planner.domain.ApplicationReceipt
import todoistcaldavsync.planner.domain.AppliedMapping
import todoistcaldavsync.planner.domain.ApplyItemStatus
import todoistcaldavsync.planner.domain.CalendarEvent
import todoistcaldavsync.planner.domain.ManagedEventIds
import todoistcaldavsync.planner.domain.ProjectionMetadata

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

class ApplicationStateStoreSpec extends Specification {

    Path dir

    def setup() {
        dir = Files.createTempDirectory('app-state-spec')
    }

    def cleanup() {
        dir?.toFile()?.deleteDir()
    }

    private AppliedMapping sample(String taskId = 't1') {
        AppliedMapping.builder()
            .taskId(taskId)
            .blockId('b1')
            .eventUid('planner-abc@todoist-planner.local')
            .slotStart(Instant.parse('2026-08-10T14:00:00Z'))
            .slotEnd(Instant.parse('2026-08-10T14:30:00Z'))
            .planId('plan-1')
            .planVersion(1)
            .planHash('abc')
            .approvalId('appr-1')
            .approvalTime(Instant.parse('2026-08-07T14:00:00Z'))
            .appliedAt(Instant.parse('2026-08-07T15:00:00Z'))
            .calendarStatus(ApplyItemStatus.APPLIED)
            .todoistStatus(ApplyItemStatus.APPLIED)
            .build()
    }

    def "sync intent and per-resource evidence survive every restart without changing resize weights"() {
        given:
        Map intent = [kind: 'resize', taskIds: ['a', 'b'], resources: [
            'task:a': [beforeMinutes: 30, afterMinutes: 20],
            'task:b': [beforeMinutes: 60, afterMinutes: 40],
            'calendar:group': [etag: 'original', start: '2026-08-10T09:00:00Z']
        ]]
        def store = new ApplicationStateStore(dir)

        when:
        store.beginSyncOperation('resize-1', intent)
        intent.resources['task:a'].beforeMinutes = 20
        store = new ApplicationStateStore(dir)
        store.checkpointSyncResource('resize-1', 'task:a', 'verified', [minutes: 20])
        store = new ApplicationStateStore(dir)
        store.checkpointSyncResource('resize-1', 'task:b', 'unknown')
        store = new ApplicationStateStore(dir)

        then:
        store.loadSyncOperations()['resize-1'].intent.resources['task:a'].beforeMinutes == 30
        store.loadSyncOperations()['resize-1'].status == 'pending'
        store.loadSyncOperations()['resize-1'].outcomes['task:b'].state == 'unknown'

        when:
        store.checkpointSyncResource('resize-1', 'task:b', 'verified', [minutes: 40])
        store = new ApplicationStateStore(dir)
        store.checkpointSyncResource('resize-1', 'calendar:group', 'verified', [etag: 'updated'])

        then:
        new ApplicationStateStore(dir).loadSyncOperations()['resize-1'].status == 'verified'
    }

    def "pending intent refuses overlaps and reuse with different baselines"() {
        given:
        def store = new ApplicationStateStore(dir)
        store.beginSyncOperation('original', [taskIds: ['a'], resources: ['cal:1': [etag: 'v1']]])

        when:
        store.beginSyncOperation(id, intent)

        then:
        thrown(IllegalStateException)

        where:
        id         | intent
        'original' | [taskIds: ['a'], resources: ['cal:1': [etag: 'changed']]]
        'other'    | [taskIds: ['a'], resources: ['cal:2': [etag: 'v2']]]
        'other'    | [taskIds: ['b'], resources: ['cal:1': [etag: 'v1']]]
    }

    def "failed atomic journal replacement retains prior checkpoint"() {
        given:
        def store = new ApplicationStateStore(dir)
        store.beginSyncOperation('op', [taskIds: ['a'], resources: ['task:a': [minutes: 30]]])
        def crashing = new ApplicationStateStore(dir, { throw new IOException('interrupted before replacement') })

        when:
        crashing.checkpointSyncResource('op', 'task:a', 'verified', [minutes: 30])

        then:
        thrown(PlanStoreException)
        new ApplicationStateStore(dir).loadSyncOperations()['op'].outcomes['task:a'].state == 'pending'
    }

    def "corrupt journal fails closed for reads and further intent creation"() {
        given:
        def store = new ApplicationStateStore(dir)
        Files.writeString(store.syncOperationsPath(), corrupt)

        when:
        store.loadSyncOperations()

        then:
        thrown(PlanStoreException)

        when:
        store.beginSyncOperation('op', [taskIds: ['a'], resources: ['task:a': [:]]])

        then:
        thrown(PlanStoreException)
        Files.readString(store.syncOperationsPath()) == corrupt

        where:
        corrupt << ['', '{', '{"schemaVersion":99,"operations":{}}',
            '{"schemaVersion":1,"operations":{"op":{"id":"op","status":"verified"}}}']
    }

    def "mutation lease refuses another store instance while held and releases after failure"() {
        given:
        def first = new ApplicationStateStore(dir)
        def second = new ApplicationStateStore(dir)

        expect:
        first.withMutationLock { first.withMutationLock { 'nested service' } } == 'nested service'

        when:
        first.withMutationLock { second.withMutationLock { throw new AssertionError('second writer ran') } }

        then:
        thrown(IllegalStateException)

        expect:
        second.withMutationLock { 'released' } == 'released'
    }

    def "save and load mappings round-trip with schema version"() {
        given:
        def store = new ApplicationStateStore(dir)
        def m = sample()

        when:
        store.putMapping(m)
        def loaded = store.loadMappings()

        then:
        loaded['t1'].taskId == 't1'
        loaded['t1'].eventUid == m.eventUid
        loaded['t1'].fullyApplied()
        new String(Files.readAllBytes(store.mappingsPath()), StandardCharsets.UTF_8).contains('"schemaVersion"')
    }

    def "historical mappings retain multiple occurrences of the same task"() {
        given:
        def store = new ApplicationStateStore(dir)
        def firstMap = new LinkedHashMap(sample('series-1').toMap())
        firstMap.occurrenceKey = 'series-1:4'
        firstMap.eventUid = 'series-1-4@todoist-planner.local'
        def secondMap = new LinkedHashMap(sample('series-1').toMap())
        secondMap.occurrenceKey = 'series-1:5'
        secondMap.eventUid = 'series-1-5@todoist-planner.local'

        when:
        store.putMapping(AppliedMapping.fromMap(firstMap))
        store.putMapping(AppliedMapping.fromMap(secondMap))
        def history = new ApplicationStateStore(dir).loadHistoricalMappings()

        then:
        history.keySet() == ['series-1:4', 'series-1:5'] as Set
        history['series-1:4'].eventUid == 'series-1-4@todoist-planner.local'
        history['series-1:5'].eventUid == 'series-1-5@todoist-planner.local'
        store.loadMappings()['series-1'].occurrenceKey == 'series-1:5'
    }

    def "legacy task-only mapping migrates idempotently into occurrence history without deleting active state"() {
        given:
        def store = new ApplicationStateStore(dir)
        Map legacy = new LinkedHashMap(sample('legacy-series').toMap())
        legacy.remove('seriesTaskId')
        legacy.remove('occurrenceKey')
        Files.createDirectories(dir)
        Files.writeString(store.mappingsPath(), JsonOutput.prettyPrint(JsonOutput.toJson(
            [schemaVersion: 1, mappings: [legacy]])), StandardCharsets.UTF_8)

        when:
        def first = store.loadHistoricalMappings()
        String firstSnapshot = Files.readString(store.mappingHistoryPath(), StandardCharsets.UTF_8)
        def second = new ApplicationStateStore(dir).loadHistoricalMappings()

        then:
        first.keySet() == ['legacy-series:0'] as Set
        first['legacy-series:0'].eventUid == legacy.eventUid
        second.keySet() == first.keySet()
        Files.readString(store.mappingHistoryPath(), StandardCharsets.UTF_8) == firstSnapshot
        store.loadMappings()['legacy-series'].eventUid == legacy.eventUid
    }

    def "receipts are append-only distinct files"() {
        given:
        def store = new ApplicationStateStore(dir)
        def r1 = ApplicationReceipt.builder()
            .id('ar-1').planId('p').planVersion(1).planHash('h').mode('approval_required')
            .startedAt(Instant.parse('2026-08-07T15:00:00Z'))
            .finishedAt(Instant.parse('2026-08-07T15:00:01Z'))
            .overallStatus(ApplyItemStatus.APPLIED)
            .items([sample()])
            .build()
        def r2 = ApplicationReceipt.builder()
            .id('ar-2').planId('p').planVersion(1).planHash('h').mode('approval_required')
            .startedAt(Instant.parse('2026-08-07T16:00:00Z'))
            .overallStatus(ApplyItemStatus.SKIPPED_IDEMPOTENT)
            .build()

        when:
        store.saveReceipt(r1)
        store.saveReceipt(r2)

        then:
        store.listReceiptIds() == ['ar-1', 'ar-2']
        store.loadReceipt('ar-1').items.size() == 1
        store.loadReceipt('ar-2').overallStatus == ApplyItemStatus.SKIPPED_IDEMPOTENT
    }

    def "missing mappings returns empty map"() {
        expect:
        new ApplicationStateStore(dir).loadMappings().isEmpty()
    }

    def "empty mappings file is corrupt error"() {
        given:
        def store = new ApplicationStateStore(dir)
        Files.createDirectories(dir)
        Files.write(store.mappingsPath(), new byte[0])

        when:
        store.loadMappings()

        then:
        thrown(PlanStoreException)
    }

    def "collision-free receipt paths for similar ids"() {
        given:
        def store = new ApplicationStateStore(dir)
        expect:
        store.receiptPath('a/b') != store.receiptPath('a_b')
    }

    def "concurrent store instances writing distinct mappings lose no updates"() {
        given:
        def storeA = new ApplicationStateStore(dir)
        def storeB = new ApplicationStateStore(dir)
        int n = 40
        def errors = Collections.synchronizedList([])
        def threads = (0..<n).collect { int i ->
            Thread.start {
                try {
                    def s = (i % 2 == 0) ? storeA : storeB
                    s.putMapping(sample("task-${i}"))
                } catch (Exception e) {
                    errors << e
                }
            }
        }

        when:
        threads*.join()
        def loaded = new ApplicationStateStore(dir).loadMappings()

        then:
        errors.isEmpty()
        loaded.size() == n
        (0..<n).every { loaded["task-${it}"]?.taskId == "task-${it}" }
    }

    def "same-task concurrent puts leave a valid last-write mapping without corruption"() {
        given:
        def storeA = new ApplicationStateStore(dir)
        def storeB = new ApplicationStateStore(dir)
        def errors = Collections.synchronizedList([])
        int rounds = 30
        def threads = [
            Thread.start {
                try {
                    rounds.times { i ->
                        storeA.putMapping(sample('shared').withStatuses(
                            ApplyItemStatus.APPLIED, ApplyItemStatus.APPLIED, null, null,
                            Instant.parse('2026-08-07T15:00:00Z').plusSeconds(i)))
                    }
                } catch (Exception e) {
                    errors << e
                }
            },
            Thread.start {
                try {
                    rounds.times { i ->
                        storeB.putMapping(sample('shared').withStatuses(
                            ApplyItemStatus.APPLIED, ApplyItemStatus.FAILED, null, "e${i}",
                            Instant.parse('2026-08-07T16:00:00Z').plusSeconds(i)))
                    }
                } catch (Exception e) {
                    errors << e
                }
            }
        ]

        when:
        threads*.join()
        def loaded = new ApplicationStateStore(dir).loadMappings()

        then:
        errors.isEmpty()
        loaded['shared'] != null
        loaded['shared'].taskId == 'shared'
        loaded['shared'].eventUid == 'planner-abc@todoist-planner.local'
        // Fully parseable — no corruption
        loaded['shared'].calendarStatus in [ApplyItemStatus.APPLIED, ApplyItemStatus.SKIPPED_IDEMPOTENT]
    }

    def "identical receipt id save does not overwrite prior receipt file"() {
        given:
        def store = new ApplicationStateStore(dir)
        def r1 = ApplicationReceipt.builder()
            .id('ar-same').planId('p').planVersion(1).planHash('h').mode('approval_required')
            .startedAt(Instant.parse('2026-08-07T15:00:00Z'))
            .overallStatus(ApplyItemStatus.APPLIED)
            .items([sample()])
            .build()
        def r2 = ApplicationReceipt.builder()
            .id('ar-same').planId('p').planVersion(1).planHash('h').mode('approval_required')
            .startedAt(Instant.parse('2026-08-07T15:00:00Z'))
            .overallStatus(ApplyItemStatus.PARTIAL)
            .errors(['second'])
            .build()

        when:
        store.saveReceipt(r1)
        store.saveReceipt(r2)
        def primary = store.loadReceipt('ar-same')

        then:
        // Primary path retains first write (append-only, no overwrite)
        primary.overallStatus == ApplyItemStatus.APPLIED
        primary.items.size() == 1
        // Index records both; directory has 2 receipt files
        Files.list(dir).withCloseable { stream ->
            stream.filter { it.fileName.toString().startsWith('receipt-') }.count()
        } >= 2
    }

    def "nested public lock-taking methods on same thread are rejected"() {
        given:
        def store = new ApplicationStateStore(dir)
        def lockMethod = ApplicationStateStore.getDeclaredMethod('withStoreLock', Closure)
        lockMethod.accessible = true

        when:
        IllegalStateException nested = null
        try {
            lockMethod.invoke(store, {
                try {
                    lockMethod.invoke(store, { 'inner' })
                } catch (java.lang.reflect.InvocationTargetException ite) {
                    def root = ite.targetException
                    while (root.cause != null && root.cause != root) {
                        root = root.cause
                    }
                    if (root instanceof IllegalStateException) {
                        nested = (IllegalStateException) root
                    } else {
                        throw ite
                    }
                }
                null
            })
        } catch (java.lang.reflect.InvocationTargetException ite) {
            def root = ite.targetException
            while (root.cause != null && root.cause != root) {
                root = root.cause
            }
            if (root instanceof IllegalStateException) {
                nested = (IllegalStateException) root
            } else {
                throw ite
            }
        }

        then:
        nested != null
        nested.message.toLowerCase().contains('nest')
    }

    def "putMapping merges under lock without full snapshot clobber from concurrent put"() {
        given:
        def storeA = new ApplicationStateStore(dir)
        def storeB = new ApplicationStateStore(dir)
        storeA.putMapping(sample('t1'))
        storeB.putMapping(sample('t2'))

        when:
        storeA.putMapping(sample('t1').withStatuses(
            ApplyItemStatus.APPLIED, ApplyItemStatus.SKIPPED_IDEMPOTENT, null, null,
            Instant.parse('2026-08-07T16:00:00Z')))
        def loaded = storeA.loadMappings()

        then:
        loaded.keySet() as Set == ['t1', 't2'] as Set
        loaded['t1'].todoistStatus == ApplyItemStatus.SKIPPED_IDEMPOTENT
        loaded['t2'].taskId == 't2'
    }

    private CalendarEvent projection(int revision = 1, String id = 'resource') {
        String uid = ManagedEventIds.uidForOccurrences(['a:0'])
        def metadata = new ProjectionMetadata([schemaVersion: 1, uid: uid, destination: 'Work',
            revision: revision, routingFingerprint: 'a' * 64, historical: false, allDay: false,
            members: [[taskId: 'a', occurrenceKey: 'a:0', start: '2026-11-24T09:00:00Z',
                end: '2026-11-24T09:30:00Z', due: [date: '2026-11-24T09:00:00Z'],
                nativeMinutes: null, effectiveMinutes: 30]]])
        CalendarEvent.builder().id(id).uid(uid).calendarName('Work').title('Task')
            .description(metadata.description('Human note', 'a', 'sync')).etag('"v' + revision + '"')
            .start(Instant.parse('2026-11-24T09:00:00Z')).end(Instant.parse('2026-11-24T09:30:00Z')).build()
    }

    def 'portable projection adoption survives state reopening and cancellation prevents recreation'() {
        given:
        def store = new ApplicationStateStore(dir)
        CalendarEvent event = projection()
        store.commitProjections([event])

        expect:
        new ApplicationStateStore(dir).loadProjections().values().first().event == ProjectionMetadata.eventMap(event)

        when:
        new ApplicationStateStore(dir).cancelProjection(event)
        new ApplicationStateStore(dir).commitProjections([event])

        then:
        thrown(IllegalStateException)
        new ApplicationStateStore(dir).loadProjections().values().first().status == 'cancelled'
    }

    def 'duplicate active membership is refused atomically without losing prior verified projection'() {
        given:
        def store = new ApplicationStateStore(dir)
        store.commitProjections([projection()])

        when:
        store.commitProjections([projection(1, 'duplicate')])

        then:
        def failure = thrown(PlanStoreException)
        failure.cause.message.contains('duplicate active occurrence coverage')
        store.loadProjections().size() == 1
        store.loadProjections().values().first().event.id == 'resource'
    }

    def 'verified operation replay cannot rewind a later membership revision'() {
        given:
        def store = new ApplicationStateStore(dir)
        [1, 2].each { int revision ->
            CalendarEvent event = projection(revision)
            String operation = 'operation-' + revision
            store.beginSyncOperation(operation, [taskIds: ['a'], resources: [
                'calendar:Work:resource': [action: 'update', event: ProjectionMetadata.eventMap(event)]]])
            store.checkpointSyncResource(operation, 'calendar:Work:resource', 'verified', [event: ProjectionMetadata.eventMap(event)])
            assert !store.syncProjectionsCommitted(operation)
            store.commitSyncProjections(operation)
            assert new ApplicationStateStore(dir).syncProjectionsCommitted(operation)
        }

        when:
        new ApplicationStateStore(dir).commitSyncProjections('operation-1')

        then:
        thrown(IllegalStateException)
        ProjectionMetadata.read(ProjectionMetadata.eventFromMap(store.loadProjections().values().first().event as Map)).value.revision == 2
    }

    def 'unverified operations and corrupt projection state do not authorize membership commit'() {
        given:
        def store = new ApplicationStateStore(dir)
        store.beginSyncOperation('pending', [taskIds: ['a'], resources: ['calendar:Work:resource': [action: 'create']]])

        when:
        store.commitProjections([projection()], [], 'pending')

        then:
        thrown(IllegalStateException)
        !Files.exists(store.projectionsPath())

        when:
        Files.writeString(store.projectionsPath(), '{"schemaVersion":999,"records":{}}')
        new ApplicationStateStore(dir).loadProjections()

        then:
        thrown(PlanStoreException)
    }
}
