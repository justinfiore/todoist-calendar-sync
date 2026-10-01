package todoistcaldavsync.planner.state

import spock.lang.Specification
import todoistcaldavsync.planner.domain.ApplicationReceipt
import todoistcaldavsync.planner.domain.AppliedMapping
import todoistcaldavsync.planner.domain.ApplyItemStatus

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
}
