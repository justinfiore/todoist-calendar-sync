package todoistcaldavsync.planner.state

import spock.lang.Specification
import todoistcaldavsync.planner.domain.MemberInterval
import todoistcaldavsync.planner.domain.Plan
import todoistcaldavsync.planner.domain.ScheduledBlock
import todoistcaldavsync.planner.domain.Task

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant

class PlanStoreSpec extends Specification {

    Path dir

    def setup() {
        dir = Files.createTempDirectory('plan-store-spec')
    }

    def cleanup() {
        dir?.toFile()?.deleteDir()
    }

    private Plan samplePlan(String id = 'p1') {
        def start = Instant.parse('2026-08-06T13:00:00Z')
        def mid = start + Duration.ofMinutes(20)
        def end = mid + Duration.ofMinutes(10)
        def t1 = Task.builder().id('a').content('A').priority(2)
            .effectiveDuration(Duration.ofMinutes(20)).durationSource('test').build()
        def t2 = Task.builder().id('b').content('B').priority(2)
            .effectiveDuration(Duration.ofMinutes(10)).durationSource('test').build()
        Plan.builder()
            .id(id)
            .createdAt(Instant.parse('2026-08-06T12:00:00Z'))
            .mode('preview')
            .tasks([t1, t2])
            .scheduledBlocks([
                ScheduledBlock.builder()
                    .id('block-f')
                    .start(start)
                    .end(end)
                    .taskIds(['a', 'b'])
                    .memberIntervals([
                        new MemberInterval('a', start, mid),
                        new MemberInterval('b', mid, end)
                    ])
                    .title('Focus')
                    .focusBlock(true)
                    .reason('test')
                    .build()
            ])
            .build()
    }

    def "save replaces existing file and leaves no temp on success"() {
        given:
        def store = new PlanStore(dir)
        def plan = samplePlan('replace-me')
        store.save(plan)
        def path = store.pathFor(plan.id)
        def first = new String(Files.readAllBytes(path), StandardCharsets.UTF_8)

        when:
        def updated = Plan.builder()
            .id(plan.id)
            .createdAt(plan.createdAt)
            .mode('preview')
            .tasks(plan.tasks)
            .scheduledBlocks(plan.scheduledBlocks)
            .humanDiff('updated-diff')
            .build()
        store.save(updated)
        def second = new String(Files.readAllBytes(path), StandardCharsets.UTF_8)
        def temps = Files.list(dir).withCloseable { s ->
            s.findAll { it.fileName.toString().endsWith('.tmp') }
        }

        then:
        first.contains('"schemaVersion"')
        second.contains('updated-diff')
        temps.isEmpty()
        store.load(plan.id).humanDiff == 'updated-diff'
    }

    def "failure before move preserves existing final file and cleans temp"() {
        given:
        def storeOk = new PlanStore(dir)
        def plan = samplePlan('durable')
        storeOk.save(plan)
        def path = storeOk.pathFor(plan.id)
        def before = new String(Files.readAllBytes(path), StandardCharsets.UTF_8)

        def failing = new PlanStore(dir, {
            throw new IOException('simulated failure before move')
        })
        def newer = Plan.builder()
            .id(plan.id)
            .createdAt(plan.createdAt)
            .mode('preview')
            .tasks(plan.tasks)
            .scheduledBlocks(plan.scheduledBlocks)
            .humanDiff('should-not-appear')
            .build()

        when:
        failing.save(newer)

        then:
        def e = thrown(PlanStoreException)
        e.context == 'save'
        e.path == path.toString()
        new String(Files.readAllBytes(path), StandardCharsets.UTF_8) == before
        !before.contains('should-not-appear')
        Files.list(dir).withCloseable { s ->
            s.every { !it.fileName.toString().endsWith('.tmp') }
        }
        storeOk.load(plan.id).humanDiff == null
    }

    def "round-trip preserves memberIntervals and schemaVersion"() {
        given:
        def store = new PlanStore(dir)
        def plan = samplePlan('members')

        when:
        store.save(plan)
        def loaded = store.load(plan.id)
        def json = new String(Files.readAllBytes(store.pathFor(plan.id)), StandardCharsets.UTF_8)

        then:
        loaded.scheduledBlocks[0].memberIntervals.size() == 2
        loaded.scheduledBlocks[0].memberIntervals[0].taskId == 'a'
        loaded.scheduledBlocks[0].memberIntervals[0].durationMinutes() == 20
        loaded.scheduledBlocks[0].memberIntervals[1].durationMinutes() == 10
        json.contains('"schemaVersion" : 2') || json.contains('"schemaVersion": 2')
        json.contains('memberIntervals')
    }

    def "load returns null when not found"() {
        expect:
        new PlanStore(dir).load('missing') == null
    }

    def "corrupt JSON throws PlanStoreException not partial plan"() {
        given:
        def store = new PlanStore(dir)
        def path = store.pathFor('bad')
        Files.write(path, '{not json'.getBytes(StandardCharsets.UTF_8))

        when:
        store.load('bad')

        then:
        def e = thrown(PlanStoreException)
        e.path == path.toString()
        e.context == 'parse'
        e.message.toLowerCase().contains('json') || e.message.toLowerCase().contains('malformed')
    }

    def "bad timestamp throws PlanStoreException"() {
        given:
        def store = new PlanStore(dir)
        def path = store.pathFor('bad-ts')
        Files.write(path, '''{
          "schemaVersion": 2,
          "id": "bad-ts",
          "createdAt": "not-an-instant",
          "mode": "preview",
          "tasks": [],
          "scheduledBlocks": []
        }'''.getBytes(StandardCharsets.UTF_8))

        when:
        store.load('bad-ts')

        then:
        def e = thrown(PlanStoreException)
        e.message.toLowerCase().contains('instant') || e.message.toLowerCase().contains('createdat')
    }

    def "missing required field throws PlanStoreException"() {
        given:
        def store = new PlanStore(dir)
        def path = store.pathFor('no-id')
        Files.write(path, '''{
          "schemaVersion": 2,
          "createdAt": "2026-08-06T12:00:00Z",
          "mode": "preview"
        }'''.getBytes(StandardCharsets.UTF_8))

        when:
        store.load('no-id')

        then:
        def e = thrown(PlanStoreException)
        e.message.toLowerCase().contains('id')
    }

    def "unsupported schemaVersion throws PlanStoreException"() {
        given:
        def store = new PlanStore(dir)
        Files.write(store.pathFor('v99'), '''{
          "schemaVersion": 99,
          "id": "v99",
          "createdAt": "2026-08-06T12:00:00Z",
          "mode": "preview"
        }'''.getBytes(StandardCharsets.UTF_8))

        when:
        store.load('v99')

        then:
        def e = thrown(PlanStoreException)
        e.message.toLowerCase().contains('schema')
    }

    def "malformed memberIntervals throw without returning partial plan"() {
        given:
        def store = new PlanStore(dir)
        Files.write(store.pathFor('bad-mi'), '''{
          "schemaVersion": 2,
          "id": "bad-mi",
          "createdAt": "2026-08-06T12:00:00Z",
          "mode": "preview",
          "tasks": [],
          "scheduledBlocks": [{
            "id": "b1",
            "start": "2026-08-06T13:00:00Z",
            "end": "2026-08-06T13:30:00Z",
            "taskIds": ["a", "b"],
            "memberIntervals": [
              {"taskId": "a", "start": "2026-08-06T13:00:00Z", "end": "2026-08-06T13:40:00Z"}
            ],
            "title": "x",
            "focusBlock": true,
            "reason": "r"
          }]
        }'''.getBytes(StandardCharsets.UTF_8))

        when:
        store.load('bad-mi')

        then:
        thrown(PlanStoreException)
    }

    def "legacy snapshot without schemaVersion still loads"() {
        given:
        def store = new PlanStore(dir)
        Files.write(store.pathFor('legacy'), '''{
          "id": "legacy",
          "createdAt": "2026-08-06T12:00:00Z",
          "mode": "preview",
          "tasks": [],
          "scheduledBlocks": [],
          "unscheduled": [],
          "changes": [],
          "explanations": []
        }'''.getBytes(StandardCharsets.UTF_8))

        when:
        def loaded = store.load('legacy')

        then:
        loaded != null
        loaded.id == 'legacy'
    }

    def "deterministic serialization includes stable schemaVersion key order"() {
        given:
        def plan = samplePlan('det')
        when:
        def j1 = PlanStore.toJson(plan)
        def j2 = PlanStore.toJson(plan)
        then:
        j1 == j2
        j1.trim().startsWith('{')
        j1.contains('schemaVersion')
    }

    def "listPlanIds returns original plan ids not sanitized filename stems"() {
        given:
        def store = new PlanStore(dir)
        def specialId = 'team/alpha:plan 1'
        def plainId = 'z-plain'
        def earlyId = 'a-early'
        store.save(samplePlan(specialId))
        store.save(samplePlan(plainId))
        store.save(samplePlan(earlyId))
        // Corrupt snapshot must not abort listing; skip or isolate without inventing ids
        Files.write(dir.resolve('plan-corrupt-stem.json'), '{not json'.getBytes(StandardCharsets.UTF_8))

        when:
        def ids = store.listPlanIds()
        def loaded = store.load(specialId)

        then:
        ids.contains(specialId)
        ids.contains(plainId)
        ids.contains(earlyId)
        // Original id with chars requiring sanitization round-trips via list + load
        loaded != null
        loaded.id == specialId
        // Stable sorted by original plan id
        ids == ids.toSorted()
        ids.indexOf(earlyId) < ids.indexOf(plainId)
        Files.exists(store.pathFor(specialId))
        // Collision-free stem: readable prefix + stable hash of exact id (not bare sanitize alone)
        def fname = store.pathFor(specialId).fileName.toString()
        fname.startsWith('plan-')
        fname.endsWith('.json')
        fname != 'plan-team_alpha_plan_1.json' || fname.contains('-')
        store.pathFor(specialId).fileName.toString() ==~ /^plan-.+-[0-9a-f]{12,}\.json$/
        !ids.contains('team_alpha_plan_1')
    }

    def "distinct ids that collide under legacy sanitize round-trip independently"() {
        given:
        def store = new PlanStore(dir)
        def slashId = 'a/b'
        def underId = 'a_b'
        // Legacy sanitize maps both to a_b — new encoding must not
        expect:
        PlanStore.legacySanitize(slashId) == PlanStore.legacySanitize(underId)
        store.pathFor(slashId) != store.pathFor(underId)

        when:
        store.save(samplePlan(slashId))
        store.save(samplePlan(underId))
        def loadedSlash = store.load(slashId)
        def loadedUnder = store.load(underId)
        def ids = store.listPlanIds()

        then:
        loadedSlash != null
        loadedUnder != null
        loadedSlash.id == slashId
        loadedUnder.id == underId
        loadedSlash.humanDiff != 'x'
        Files.exists(store.pathFor(slashId))
        Files.exists(store.pathFor(underId))
        store.pathFor(slashId).fileName != store.pathFor(underId).fileName
        ids.containsAll([slashId, underId])
        ids == ids.toSorted()
        // Saving one must not overwrite the other
        store.save(Plan.builder().id(slashId).createdAt(Instant.parse('2026-08-06T12:00:00Z'))
            .mode('preview').tasks([]).humanDiff('slash-only').build())
        store.load(underId).id == underId
        store.load(slashId).humanDiff == 'slash-only'
        store.load(underId).humanDiff != 'slash-only'
    }

    def "unicode spaces dots and traversal-looking ids round-trip without path escape"() {
        given:
        def store = new PlanStore(dir)
        def ids = [
            'café plan',
            'foo.bar.baz',
            '../etc/passwd',
            'a b c',
            '日本語',
            'dot.',
            '.hidden'
        ]

        when:
        ids.each { store.save(samplePlan(it)) }
        def loaded = ids.collectEntries { [(it): store.load(it)] }
        def listed = store.listPlanIds()
        def paths = ids.collect { store.pathFor(it) }

        then:
        ids.every { loaded[it] != null && loaded[it].id == it }
        listed.containsAll(ids)
        listed == listed.toSorted()
        paths.every { it.parent == dir }
        paths.every { !it.fileName.toString().contains('/') && !it.fileName.toString().contains('\\') }
        paths.every { it.fileName.toString() ==~ /^plan-.+\.json$/ }
        paths.unique().size() == ids.size()
    }

    def "legacy sanitized snapshot remains loadable without colliding with different id"() {
        given:
        def store = new PlanStore(dir)
        def legacyId = 'team/alpha:plan 1'
        // Pre-existing file using legacy sanitize stem only (no hash suffix)
        def legacyStem = PlanStore.legacySanitize(legacyId)
        def legacyPath = dir.resolve("plan-${legacyStem}.json")
        def legacyJson = PlanStore.toJson(samplePlan(legacyId))
        Files.write(legacyPath, legacyJson.getBytes(StandardCharsets.UTF_8))
        // A different id that would share the legacy stem must not be returned for legacyId
        def collidingOther = 'team_alpha_plan_1'
        assert PlanStore.legacySanitize(legacyId) == PlanStore.legacySanitize(collidingOther)

        when:
        def fromLegacy = store.load(legacyId)
        // New save of collidingOther uses collision-free path and must not clobber legacy file content id
        store.save(samplePlan(collidingOther))
        def stillLegacy = store.load(legacyId)
        def other = store.load(collidingOther)

        then:
        fromLegacy != null
        fromLegacy.id == legacyId
        stillLegacy != null
        stillLegacy.id == legacyId
        other != null
        other.id == collidingOther
        Files.exists(legacyPath)
        store.pathFor(collidingOther) != legacyPath
        // load must never return a snapshot whose embedded id differs from the requested id
        store.load(legacyId).id == legacyId
        store.load(collidingOther).id == collidingOther
    }
}
