package todoistcaldavsync.planner.state

import spock.lang.Specification
import todoistcaldavsync.planner.domain.DecisionRecord
import todoistcaldavsync.planner.domain.DeliveryReceipt

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class DeliveryAndDecisionStoreSpec extends Specification {

    def dirs = []

    def cleanup() {
        dirs.each {
            try {
                Files.walk(it).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            } catch (Exception ignored) {
            }
        }
    }

    private Path temp() {
        def d = Files.createTempDirectory('ledger-')
        dirs << d
        d
    }

    def "delivery ledger records delivered and blocks duplicates"() {
        given:
        def ledger = new DeliveryLedger(temp())
        def now = Instant.parse('2026-08-07T12:00:00Z')
        def r = DeliveryReceipt.builder()
            .id('dlv-1').idempotencyKey('key-a').kind('daily_summary')
            .destination('#p').status('DELIVERED').attemptedAt(now).completedAt(now)
            .build()

        when:
        ledger.recordPending(DeliveryReceipt.builder()
            .id('pend-1').idempotencyKey('key-a').kind('daily_summary')
            .destination('#p').status('PENDING').attemptedAt(now).build())
        ledger.recordDelivered(r)
        def found = ledger.findDelivered('key-a')
        def failed = DeliveryReceipt.builder()
            .id('dlv-2').idempotencyKey('key-b').kind('daily_summary')
            .destination('#p').status('FAILED').attemptedAt(now).completedAt(now)
            .errorClassification('TRANSPORT').errorMessage('x').build()
        ledger.recordFailed(failed)

        then:
        found != null
        found.id == 'dlv-1'
        ledger.findDelivered('key-b') == null
        ledger.wasDelivered('key-a')
        !ledger.wasDelivered('key-b')
    }

    def "failed delivery never indexes as delivered"() {
        given:
        def ledger = new DeliveryLedger(temp())
        def now = Instant.now()
        ledger.recordFailed(DeliveryReceipt.builder()
            .id('f1').idempotencyKey('k').kind('x').destination('#')
            .status('FAILED').attemptedAt(now).completedAt(now).build())

        expect:
        !ledger.wasDelivered('k')
        ledger.findLatest('k').status == 'FAILED'
        !ledger.blocksResend('k')
    }

    def "PENDING and UNKNOWN block resend; FAILED allows; DELIVERED blocks"() {
        given:
        def ledger = new DeliveryLedger(temp())
        def now = Instant.now()

        when:
        ledger.recordPending(DeliveryReceipt.builder()
            .id('p1').idempotencyKey('k-pend').kind('x').destination('#')
            .status('PENDING').attemptedAt(now).build())
        ledger.recordPending(DeliveryReceipt.builder()
            .id('p-unk').idempotencyKey('k-unk').kind('x').destination('#')
            .status('PENDING').attemptedAt(now).build())
        ledger.transition('k-unk', ['PENDING'] as Set, DeliveryReceipt.builder()
            .id('u1').idempotencyKey('k-unk').kind('x').destination('#')
            .status('UNKNOWN').attemptedAt(now).completedAt(now).build())
        ledger.recordPending(DeliveryReceipt.builder()
            .id('p-del').idempotencyKey('k-del').kind('x').destination('#')
            .status('PENDING').attemptedAt(now).build())
        ledger.recordDelivered(DeliveryReceipt.builder()
            .id('d1').idempotencyKey('k-del').kind('x').destination('#')
            .status('DELIVERED').attemptedAt(now).completedAt(now).build())
        ledger.recordFailed(DeliveryReceipt.builder()
            .id('f1').idempotencyKey('k-fail').kind('x').destination('#')
            .status('FAILED').attemptedAt(now).completedAt(now).build())

        then:
        ledger.blocksResend('k-pend')
        ledger.blocksResend('k-unk')
        ledger.blocksResend('k-del')
        !ledger.blocksResend('k-fail')
        !ledger.blocksResend('missing')
    }

    def "transition PENDING to DELIVERED is atomic under key"() {
        given:
        def ledger = new DeliveryLedger(temp())
        def now = Instant.now()
        ledger.recordPending(DeliveryReceipt.builder()
            .id('pend').idempotencyKey('k-t').kind('x').destination('#')
            .status('PENDING').attemptedAt(now).build())

        when:
        def next = DeliveryReceipt.builder()
            .id('ok').idempotencyKey('k-t').kind('x').destination('#')
            .status('DELIVERED').providerMessageId('ts-1')
            .attemptedAt(now).completedAt(now).build()
        ledger.transition('k-t', ['PENDING', 'ATTEMPT'] as Set, next)

        then:
        ledger.findDelivered('k-t').id == 'ok'
        ledger.findLatest('k-t').status == 'DELIVERED'

        when: 'transition from wrong status refused'
        ledger.transition('k-t', ['PENDING'] as Set, DeliveryReceipt.builder()
            .id('again').idempotencyKey('k-t').kind('x').destination('#')
            .status('DELIVERED').attemptedAt(now).completedAt(now).build())

        then:
        thrown(PlanStoreException)
        ledger.findDelivered('k-t').id == 'ok'
    }

    def "multiple ledger instances concurrent distinct keys no lost records"() {
        given:
        def dir = temp()
        def n = 24
        def pool = Executors.newFixedThreadPool(6)
        def latch = new CountDownLatch(1)
        def errors = new AtomicInteger(0)

        when:
        def futures = (0..<n).collect { i ->
            pool.submit {
                latch.await(5, TimeUnit.SECONDS)
                try {
                    def ledger = new DeliveryLedger(dir)
                    def now = Instant.now()
                    ledger.recordPending(DeliveryReceipt.builder()
                        .id("pend-c-${i}").idempotencyKey("key-${i}").kind('daily_summary')
                        .destination('#p').status('PENDING').attemptedAt(now).build())
                    ledger.recordDelivered(DeliveryReceipt.builder()
                        .id("dlv-c-${i}").idempotencyKey("key-${i}").kind('daily_summary')
                        .destination('#p').status('DELIVERED')
                        .providerMessageId("ts-${i}")
                        .attemptedAt(now).completedAt(now).build())
                } catch (Exception e) {
                    errors.incrementAndGet()
                }
            }
        }
        latch.countDown()
        futures.each { it.get(60, TimeUnit.SECONDS) }
        pool.shutdown()
        def ledger = new DeliveryLedger(dir)

        then:
        errors.get() == 0
        ledger.listReceiptIds().size() == n * 2
        (0..<n).every { i -> ledger.wasDelivered("key-${i}") }
        (0..<n).every { i -> ledger.findDelivered("key-${i}").id == "dlv-c-${i}" }
    }

    def "same key concurrent writers deterministic first-delivered wins"() {
        given:
        def dir = temp()
        def pool = Executors.newFixedThreadPool(4)
        def latch = new CountDownLatch(1)
        def n = 8

        when:
        def futures = (0..<n).collect { i ->
            pool.submit {
                latch.await(5, TimeUnit.SECONDS)
                def now = Instant.now()
                def L = new DeliveryLedger(dir)
                def claim = L.tryClaimPending('shared-key', DeliveryReceipt.builder()
                    .id("pend-${i}").idempotencyKey('shared-key').kind('k').destination('#')
                    .status('PENDING').attemptedAt(now).build())
                if (claim.claimed) {
                    L.recordDelivered(DeliveryReceipt.builder()
                        .id("same-${i}").idempotencyKey('shared-key').kind('k').destination('#')
                        .status('DELIVERED').providerMessageId("ts-${i}")
                        .attemptedAt(now).completedAt(now).build())
                }
            }
        }
        latch.countDown()
        futures.each { it.get(60, TimeUnit.SECONDS) }
        pool.shutdown()
        def winner = new DeliveryLedger(dir).findDelivered('shared-key')

        then:
        winner != null
        winner.id.startsWith('same-')
        // first-delivered wins remains stable on re-read
        new DeliveryLedger(dir).findDelivered('shared-key').id == winner.id
    }

    def "beforeMoveHook interruption preserves prior snapshot and cleans temp"() {
        given:
        def dir = temp()
        def now = Instant.now()
        def okLedger = new DeliveryLedger(dir)
        okLedger.recordPending(DeliveryReceipt.builder()
            .id('prior-p').idempotencyKey('k-hook').kind('x').destination('#')
            .status('PENDING').attemptedAt(now).build())
        okLedger.recordDelivered(DeliveryReceipt.builder()
            .id('prior').idempotencyKey('k-hook').kind('x').destination('#')
            .status('DELIVERED').providerMessageId('ts-prior')
            .attemptedAt(now).completedAt(now).build())
        def boom = new AtomicInteger(0)
        def hooked = new DeliveryLedger(dir, {
            if (boom.getAndIncrement() == 0) {
                throw new RuntimeException('crash before move')
            }
        })

        when:
        hooked.recordPending(DeliveryReceipt.builder()
            .id('new-p').idempotencyKey('k-hook-2').kind('x').destination('#')
            .status('PENDING').attemptedAt(now).build())

        then:
        thrown(Exception)
        // prior snapshot intact
        new DeliveryLedger(dir).findDelivered('k-hook').id == 'prior'
        // no leftover temp files
        !Files.list(dir).anyMatch { it.fileName.toString().endsWith('.tmp') }
    }

    def "corrupted ledger index surfaces structured PlanStoreException"() {
        given:
        def dir = temp()
        Files.createDirectories(dir)
        Files.writeString(dir.resolve('delivery-index.json'), '{not-json')

        when:
        new DeliveryLedger(dir).wasDelivered('k')

        then:
        def e = thrown(PlanStoreException)
        e.message.toLowerCase().contains('malformed') || e.message.toLowerCase().contains('parse')
        e.path != null || e.context != null
    }

    def "decision store append-only and list by proposal/correlation"() {
        given:
        def store = new DecisionStore(temp())
        def now = Instant.parse('2026-08-07T12:00:00Z')
        def d1 = DecisionRecord.builder()
            .id('dec-1').proposalId('prop-1').planId('p').planVersion(1)
            .planHash('abc').action('APPROVE').status('ACCEPTED')
            .actorId('u1').correlationId('c1').decidedAt(now).build()
        def d2 = DecisionRecord.builder()
            .id('dec-2').proposalId('prop-1').planId('p').planVersion(1)
            .planHash('abc').action('STATUS').status('ACCEPTED')
            .actorId('u1').correlationId('c2').decidedAt(now).build()

        when:
        store.appendClassified(d1)
        store.appendAudit(d2)

        then:
        store.load('dec-1').action == 'APPROVE'
        store.listForProposal('prop-1')*.id == ['dec-1', 'dec-2']
        store.listForCorrelation('c1')*.id == ['dec-1']

        when: 'same identity replay appends durable IDEMPOTENT_REPLAY with distinct id'
        def replayOut = store.appendClassified(d1)

        then:
        replayOut.isIdempotentReplay()
        replayOut.persisted.id != 'dec-1'
        replayOut.persisted.id.startsWith('dec-replay-')
        store.load(replayOut.persisted.id).status == 'IDEMPOTENT_REPLAY'
        store.listForCorrelation('c1').size() == 2
    }

    def "decision store beforeMoveHook interruption leaves no corrupt primary"() {
        given:
        def dir = temp()
        def boom = new AtomicInteger(0)
        def store = new DecisionStore(dir, {
            if (boom.getAndIncrement() == 0) {
                throw new RuntimeException('crash before move')
            }
        })
        def now = Instant.now()
        def d = DecisionRecord.builder()
            .id('dec-x').action('REJECT').status('ACCEPTED')
            .actorId('a').decidedAt(now).planId('p').planVersion(1).planHash('h')
            .proposalId('prop').correlationId('c-crash').build()

        when:
        store.appendClassified(d)

        then:
        thrown(Exception)
        store.load('dec-x') == null
    }

    def "concurrent decision appends are serialized"() {
        given:
        def store = new DecisionStore(temp())
        def pool = Executors.newFixedThreadPool(4)
        def latch = new CountDownLatch(1)
        def errors = new AtomicInteger(0)
        def n = 20

        when:
        def futures = (0..<n).collect { i ->
            pool.submit {
                latch.await(5, TimeUnit.SECONDS)
                try {
                    store.appendAudit(DecisionRecord.builder()
                        .id("dec-c-${i}").action('STATUS').status('ACCEPTED')
                        .actorId('a').correlationId("corr-${i}")
                        .proposalId('prop-c').planId('p').planVersion(1).planHash('h')
                        .decidedAt(Instant.now()).build())
                } catch (Exception e) {
                    errors.incrementAndGet()
                }
            }
        }
        latch.countDown()
        futures.each { it.get(30, TimeUnit.SECONDS) }
        pool.shutdown()

        then:
        errors.get() == 0
        store.listIds().size() == n
    }

    def "raw append is not public; hosts must use appendClassified or appendAudit"() {
        given:
        def store = new DecisionStore(temp())
        def methods = store.class.declaredMethods.findAll { it.name == 'append' }
        def publicAppend = store.class.methods.findAll {
            it.name == 'append' && it.declaringClass == DecisionStore &&
                java.lang.reflect.Modifier.isPublic(it.modifiers)
        }

        expect:
        publicAppend.isEmpty()
        methods.every { java.lang.reflect.Modifier.isPrivate(it.modifiers) }
        store.metaClass.respondsTo(store, 'appendClassified').size() >= 1
        store.metaClass.respondsTo(store, 'appendAudit').size() >= 1
    }

    def "appendAudit refuses authorizing ACCEPTED plan-bound actions; allows HELP and rejected"() {
        given:
        def store = new DecisionStore(temp())
        def now = Instant.parse('2026-08-07T12:00:00Z')
        def approve = DecisionRecord.builder()
            .id('dec-bad-auth').proposalId('prop-1').planId('p').planVersion(1)
            .planHash('abc').action('APPROVE').status('ACCEPTED')
            .actorId('u1').correlationId('c-audit-auth').decidedAt(now).build()
        def help = DecisionRecord.builder()
            .id('dec-help').action('HELP').status('ACCEPTED')
            .actorId('u1').correlationId('c-help').decidedAt(now).build()
        def rejected = DecisionRecord.builder()
            .id('dec-rej').action('APPROVE').status('REJECTED_MALFORMED')
            .actorId('u1').correlationId('c-rej').decidedAt(now)
            .reason('bad').build()

        when:
        store.appendAudit(approve)

        then:
        thrown(IllegalArgumentException)
        store.load('dec-bad-auth') == null

        when:
        def helpOut = store.appendAudit(help)
        def rejOut = store.appendAudit(rejected)

        then:
        helpOut.persisted.id == 'dec-help'
        rejOut.persisted.id == 'dec-rej'
        store.load('dec-help').action == 'HELP'
        store.load('dec-rej').status == 'REJECTED_MALFORMED'
    }

    def "appendAudit enforces correlation uniqueness like appendClassified"() {
        given:
        def store = new DecisionStore(temp())
        def now = Instant.parse('2026-08-07T12:00:00Z')
        def first = DecisionRecord.builder()
            .id('dec-a1').action('STATUS').status('ACCEPTED')
            .actorId('u1').correlationId('corr-audit').decidedAt(now).build()
        def same = DecisionRecord.builder()
            .id('dec-a2').action('STATUS').status('ACCEPTED')
            .actorId('u1').correlationId('corr-audit').decidedAt(now).build()
        def conflict = DecisionRecord.builder()
            .id('dec-a3').action('HELP').status('ACCEPTED')
            .actorId('u1').correlationId('corr-audit').decidedAt(now).build()

        when:
        def o1 = store.appendAudit(first)
        def o2 = store.appendAudit(same)
        def o3 = store.appendAudit(conflict)

        then:
        o1.isNewAccepted()
        o1.persisted.status == 'ACCEPTED'
        o2.isIdempotentReplay()
        o2.persisted.status == 'IDEMPOTENT_REPLAY'
        o3.isConflict()
        o3.persisted.status == 'REJECTED_REPLAY_CONFLICT'
    }

    def "delivery first-delivered wins for idempotency key"() {
        given:
        def ledger = new DeliveryLedger(temp())
        def now = Instant.now()
        ledger.recordPending(DeliveryReceipt.builder()
            .id('first-p').idempotencyKey('same').kind('k').destination('#')
            .status('PENDING').attemptedAt(now).build())
        ledger.recordDelivered(DeliveryReceipt.builder()
            .id('first').idempotencyKey('same').kind('k').destination('#')
            .status('DELIVERED').providerMessageId('ts-1')
            .attemptedAt(now).completedAt(now).build())
        ledger.recordDelivered(DeliveryReceipt.builder()
            .id('second').idempotencyKey('same').kind('k').destination('#')
            .status('DELIVERED').providerMessageId('ts-2')
            .attemptedAt(now).completedAt(now).build())

        expect:
        ledger.findDelivered('same').id == 'first'
    }

    def "tryClaimPending succeeds when absent or FAILED; refuses PENDING UNKNOWN DELIVERED"() {
        given:
        def ledger = new DeliveryLedger(temp())
        def now = Instant.now()
        def pend = { String id, String key ->
            DeliveryReceipt.builder().id(id).idempotencyKey(key).kind('k').destination('#')
                .status('PENDING').attemptedAt(now).build()
        }

        when: 'absent key claims'
        def c1 = ledger.tryClaimPending('k-new', pend('p1', 'k-new'))

        then:
        c1.claimed
        ledger.findLatest('k-new').status == 'PENDING'

        when: 'PENDING refuses second claim'
        def c2 = ledger.tryClaimPending('k-new', pend('p2', 'k-new'))

        then:
        !c2.claimed
        c2.existing.status == 'PENDING'
        c2.reason.contains('PENDING') || c2.reason.contains('NOT_CLAIMABLE')

        when: 'FAILED allows re-claim'
        ledger.transition('k-new', ['PENDING'] as Set, DeliveryReceipt.builder()
            .id('f1').idempotencyKey('k-new').kind('k').destination('#')
            .status('FAILED').attemptedAt(now).completedAt(now).build())
        def c3 = ledger.tryClaimPending('k-new', pend('p3', 'k-new'))

        then:
        c3.claimed
        ledger.findLatest('k-new').id == 'p3'

        when: 'DELIVERED refuses and is not overwritten'
        ledger.transition('k-new', ['PENDING'] as Set, DeliveryReceipt.builder()
            .id('d1').idempotencyKey('k-new').kind('k').destination('#')
            .status('DELIVERED').providerMessageId('ts').attemptedAt(now).completedAt(now).build())
        def c4 = ledger.tryClaimPending('k-new', pend('p4', 'k-new'))

        then:
        !c4.claimed
        c4.reason == 'ALREADY_DELIVERED'
        ledger.findDelivered('k-new').id == 'd1'

        when: 'UNKNOWN refuses'
        ledger.recordPending(DeliveryReceipt.builder()
            .id('pu0').idempotencyKey('k-unk').kind('k').destination('#')
            .status('PENDING').attemptedAt(now).build())
        ledger.transition('k-unk', ['PENDING'] as Set, DeliveryReceipt.builder()
            .id('u1').idempotencyKey('k-unk').kind('k').destination('#')
            .status('UNKNOWN').attemptedAt(now).completedAt(now).build())
        def c5 = ledger.tryClaimPending('k-unk', pend('pu', 'k-unk'))

        then:
        !c5.claimed
        ledger.findLatest('k-unk').status == 'UNKNOWN'
    }

    def "tryClaimPending concurrent two instances same key exactly one claim"() {
        given:
        def dir = temp()
        def pool = Executors.newFixedThreadPool(2)
        def barrier = new java.util.concurrent.CyclicBarrier(2)
        def claims = new AtomicInteger(0)
        def refuses = new AtomicInteger(0)
        def now = Instant.now()

        when:
        def futures = (0..<2).collect { i ->
            pool.submit {
                barrier.await(5, TimeUnit.SECONDS)
                def ledger = new DeliveryLedger(dir)
                def r = ledger.tryClaimPending('shared', DeliveryReceipt.builder()
                    .id("pend-${i}").idempotencyKey('shared').kind('k').destination('#')
                    .status('PENDING').attemptedAt(now).build())
                if (r.claimed) {
                    claims.incrementAndGet()
                } else {
                    refuses.incrementAndGet()
                }
            }
        }
        futures.each { it.get(30, TimeUnit.SECONDS) }
        pool.shutdown()

        then:
        claims.get() == 1
        refuses.get() == 1
        new DeliveryLedger(dir).findLatest('shared').status == 'PENDING'
    }

    def "tryClaimPending distinct keys both claim"() {
        given:
        def dir = temp()
        def now = Instant.now()
        def a = new DeliveryLedger(dir)
        def b = new DeliveryLedger(dir)

        when:
        def ca = a.tryClaimPending('key-a', DeliveryReceipt.builder()
            .id('pa').idempotencyKey('key-a').kind('k').destination('#')
            .status('PENDING').attemptedAt(now).build())
        def cb = b.tryClaimPending('key-b', DeliveryReceipt.builder()
            .id('pb').idempotencyKey('key-b').kind('k').destination('#')
            .status('PENDING').attemptedAt(now).build())

        then:
        ca.claimed
        cb.claimed
    }

    def "tampered delivery index path traversal is structured corruption and does not leak sentinel"() {
        given:
        def dir = temp()
        def secretParent = dir.parent
        def secret = secretParent.resolve('secret-outside.json')
        Files.writeString(secret, '{"id":"LEAKED_SECRET","idempotencyKey":"x","status":"DELIVERED","attemptedAt":"2026-08-07T00:00:00Z"}')
        dirs << secret
        Files.createDirectories(dir)
        // relative traversal
        Files.writeString(dir.resolve('delivery-index.json'), '''
{
  "schemaVersion": 1,
  "entries": [{"id":"evil","file":"../secret-outside.json","idempotencyKey":"k","status":"DELIVERED"}],
  "byIdempotency": {},
  "byLatest": {"k": {"receiptId":"evil","file":"../secret-outside.json","status":"DELIVERED"}}
}
''')

        when:
        new DeliveryLedger(dir).loadReceipt('evil')

        then:
        def e = thrown(PlanStoreException)
        e.message.toLowerCase().contains('..') || e.message.toLowerCase().contains('outside') ||
            e.message.toLowerCase().contains('escape') || e.context != null
        !e.message.contains('LEAKED_SECRET')

        when: 'absolute path'
        Files.writeString(dir.resolve('delivery-index.json'), """
{
  "schemaVersion": 1,
  "entries": [{"id":"abs","file":"${secret.toAbsolutePath()}","idempotencyKey":"k2","status":"DELIVERED"}],
  "byIdempotency": {},
  "byLatest": {}
}
""")
        new DeliveryLedger(dir).loadReceipt('abs')

        then:
        def e2 = thrown(PlanStoreException)
        !e2.message.contains('LEAKED_SECRET')
    }

    def "tampered delivery index symlink escape is structured corruption"() {
        given:
        def dir = temp()
        def outside = Files.createTempDirectory('outside-')
        dirs << outside
        def secret = outside.resolve('secret.json')
        Files.writeString(secret, '{"id":"SYM_LEAK","idempotencyKey":"x","status":"DELIVERED","attemptedAt":"2026-08-07T00:00:00Z"}')
        Files.createDirectories(dir)
        Path link = dir.resolve('link-out.json')
        try {
            Files.createSymbolicLink(link, secret)
        } catch (Exception ex) {
            // skip if symlinks unsupported
            return
        }
        Files.writeString(dir.resolve('delivery-index.json'), '''
{
  "schemaVersion": 1,
  "entries": [{"id":"sym","file":"link-out.json","idempotencyKey":"ks","status":"DELIVERED"}],
  "byIdempotency": {},
  "byLatest": {}
}
''')

        when:
        new DeliveryLedger(dir).loadReceipt('sym')

        then:
        def e = thrown(PlanStoreException)
        !e.message.contains('SYM_LEAK')
    }

    def "tampered decision index path traversal refused"() {
        given:
        def dir = temp()
        Files.createDirectories(dir)
        Files.writeString(dir.resolve('decision-index.json'), '''
{
  "schemaVersion": 1,
  "entries": [{"id":"evil","file":"../secret","proposalId":"p"}],
  "byCorrelation": {},
  "byProposal": {}
}
''')

        when:
        new DecisionStore(dir).load('evil')

        then:
        thrown(PlanStoreException)
    }

    def "DELIVERED is terminal: demotion to FAILED/PENDING refused; claim refused; indexes stay delivered"() {
        given:
        def ledger = new DeliveryLedger(temp())
        def now = Instant.now()
        ledger.recordPending(DeliveryReceipt.builder()
            .id('d1-p').idempotencyKey('term-k').kind('k').destination('#')
            .status('PENDING').attemptedAt(now).build())
        ledger.recordDelivered(DeliveryReceipt.builder()
            .id('d1').idempotencyKey('term-k').kind('k').destination('#')
            .status('DELIVERED').providerMessageId('ts-1')
            .attemptedAt(now).completedAt(now).build())

        when: 'delivered → failed refused'
        ledger.transition('term-k', ['DELIVERED'] as Set, DeliveryReceipt.builder()
            .id('f-bad').idempotencyKey('term-k').kind('k').destination('#')
            .status('FAILED').attemptedAt(now).completedAt(now).build())

        then:
        def e1 = thrown(DeliveryLedger.IllegalTransitionException)
        e1.toStatus == 'FAILED'
        ledger.findDelivered('term-k').id == 'd1'
        ledger.findLatest('term-k').status == 'DELIVERED'

        when: 'delivered → pending via transition refused'
        ledger.transition('term-k', ['DELIVERED'] as Set, DeliveryReceipt.builder()
            .id('p-bad').idempotencyKey('term-k').kind('k').destination('#')
            .status('PENDING').attemptedAt(now).build())

        then:
        thrown(DeliveryLedger.IllegalTransitionException)
        ledger.findLatest('term-k').status == 'DELIVERED'
        ledger.findDelivered('term-k').id == 'd1'

        when: 'claim refused forever'
        def claim = ledger.tryClaimPending('term-k', DeliveryReceipt.builder()
            .id('p-claim').idempotencyKey('term-k').kind('k').destination('#')
            .status('PENDING').attemptedAt(now).build())

        then:
        !claim.claimed
        claim.reason == 'ALREADY_DELIVERED'
        ledger.blocksResend('term-k')
    }

    def "tryClaimPending inspects byIdempotency barrier not only latest"() {
        given:
        def dir = temp()
        def ledger = new DeliveryLedger(dir)
        def now = Instant.now()
        // Delivered first
        ledger.recordPending(DeliveryReceipt.builder()
            .id('first-p').idempotencyKey('barrier-k').kind('k').destination('#')
            .status('PENDING').attemptedAt(now).build())
        ledger.recordDelivered(DeliveryReceipt.builder()
            .id('first-d').idempotencyKey('barrier-k').kind('k').destination('#')
            .status('DELIVERED').providerMessageId('ts')
            .attemptedAt(now).completedAt(now).build())
        // Tamper index: set byLatest to FAILED while byIdempotency keeps DELIVERED
        def indexPath = dir.resolve('delivery-index.json')
        def text = Files.readString(indexPath)
        // Ensure byLatest points at a fake failed-looking entry id while barrier remains
        def slurped = new groovy.json.JsonSlurper().parseText(text) as Map
        slurped.byLatest = [
            'barrier-k': [receiptId: 'first-d', file: 'x', status: 'FAILED']
        ]
        Files.writeString(indexPath, groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(slurped)))

        when:
        def claim = new DeliveryLedger(dir).tryClaimPending('barrier-k', DeliveryReceipt.builder()
            .id('sneak').idempotencyKey('barrier-k').kind('k').destination('#')
            .status('PENDING').attemptedAt(now).build())

        then:
        !claim.claimed
        claim.reason == 'ALREADY_DELIVERED'
        new DeliveryLedger(dir).wasDelivered('barrier-k')
    }

    def "FAILED to PENDING allowed; PENDING to DELIVERED FAILED UNKNOWN; UNKNOWN cannot auto-reclaim"() {
        given:
        def ledger = new DeliveryLedger(temp())
        def now = Instant.now()
        ledger.recordFailed(DeliveryReceipt.builder()
            .id('f0').idempotencyKey('sm-k').kind('k').destination('#')
            .status('FAILED').attemptedAt(now).completedAt(now).build())

        when:
        def c = ledger.tryClaimPending('sm-k', DeliveryReceipt.builder()
            .id('p0').idempotencyKey('sm-k').kind('k').destination('#')
            .status('PENDING').attemptedAt(now).build())

        then:
        c.claimed

        when:
        ledger.transition('sm-k', ['PENDING'] as Set, DeliveryReceipt.builder()
            .id('u0').idempotencyKey('sm-k').kind('k').destination('#')
            .status('UNKNOWN').attemptedAt(now).completedAt(now).build())
        def refuse = ledger.tryClaimPending('sm-k', DeliveryReceipt.builder()
            .id('p1').idempotencyKey('sm-k').kind('k').destination('#')
            .status('PENDING').attemptedAt(now).build())

        then:
        !refuse.claimed
        ledger.findLatest('sm-k').status == 'UNKNOWN'

        when: 'audited reconcile UNKNOWN → DELIVERED'
        ledger.reconcile('sm-k', DeliveryReceipt.builder()
            .id('d0').idempotencyKey('sm-k').kind('k').destination('#')
            .status('DELIVERED').providerMessageId('ts')
            .attemptedAt(now).completedAt(now).build(), 'ops confirmed')

        then:
        ledger.findDelivered('sm-k').id == 'd0'
        ledger.findLatest('sm-k').status == 'DELIVERED'
    }

    def "concurrent transition demotion of DELIVERED all refused; barrier holds"() {
        given:
        def dir = temp()
        def ledger = new DeliveryLedger(dir)
        def now = Instant.now()
        ledger.recordPending(DeliveryReceipt.builder()
            .id('d0-p').idempotencyKey('conc-t').kind('k').destination('#')
            .status('PENDING').attemptedAt(now).build())
        ledger.recordDelivered(DeliveryReceipt.builder()
            .id('d0').idempotencyKey('conc-t').kind('k').destination('#')
            .status('DELIVERED').providerMessageId('ts-0')
            .attemptedAt(now).completedAt(now).build())
        def pool = Executors.newFixedThreadPool(4)
        def latch = new CountDownLatch(1)
        def refused = new AtomicInteger(0)

        when:
        def futures = (0..<4).collect { i ->
            pool.submit {
                latch.await(5, TimeUnit.SECONDS)
                def L = new DeliveryLedger(dir)
                try {
                    L.transition('conc-t', ['DELIVERED'] as Set, DeliveryReceipt.builder()
                        .id("f-${i}").idempotencyKey('conc-t').kind('k').destination('#')
                        .status('FAILED').attemptedAt(now).completedAt(now).build())
                } catch (DeliveryLedger.IllegalTransitionException | PlanStoreException ex) {
                    refused.incrementAndGet()
                }
            }
        }
        latch.countDown()
        futures.each { it.get(30, TimeUnit.SECONDS) }
        pool.shutdown()

        then:
        refused.get() == 4
        new DeliveryLedger(dir).wasDelivered('conc-t')
        new DeliveryLedger(dir).findDelivered('conc-t').id == 'd0'
        new DeliveryLedger(dir).findLatest('conc-t').status == 'DELIVERED'
    }

    def "public state-machine APIs reject illegal states; raw record cannot demote DELIVERED"() {
        given:
        def ledger = new DeliveryLedger(temp())
        def now = Instant.now()

        when:
        ledger.recordPending(DeliveryReceipt.builder()
            .id('x').idempotencyKey('api-k').kind('k').destination('#')
            .status('DELIVERED').attemptedAt(now).completedAt(now).build())

        then:
        thrown(DeliveryLedger.IllegalTransitionException)

        when: 'FAILED→DELIVERED without PENDING claim refused'
        ledger.recordFailed(DeliveryReceipt.builder()
            .id('f0').idempotencyKey('api-fail').kind('k').destination('#')
            .status('FAILED').attemptedAt(now).completedAt(now).build())
        ledger.recordDelivered(DeliveryReceipt.builder()
            .id('d-bad').idempotencyKey('api-fail').kind('k').destination('#')
            .status('DELIVERED').attemptedAt(now).completedAt(now).build())

        then:
        thrown(DeliveryLedger.IllegalTransitionException)
        !ledger.wasDelivered('api-fail')

        when: 'claim then deliver then demote refused'
        ledger.recordPending(DeliveryReceipt.builder()
            .id('p-ok').idempotencyKey('api-k').kind('k').destination('#')
            .status('PENDING').attemptedAt(now).build())
        ledger.recordDelivered(DeliveryReceipt.builder()
            .id('d').idempotencyKey('api-k').kind('k').destination('#')
            .status('DELIVERED').attemptedAt(now).completedAt(now).build())
        ledger.recordFailed(DeliveryReceipt.builder()
            .id('f').idempotencyKey('api-k').kind('k').destination('#')
            .status('FAILED').attemptedAt(now).completedAt(now).build())

        then:
        thrown(DeliveryLedger.IllegalTransitionException)
        ledger.findDelivered('api-k').id == 'd'
    }

    def "delivery ledger recovers orphan receipt after data-before-index crash"() {
        given:
        def dir = temp()
        def now = Instant.now()
        // Establish PENDING claim without crash hook
        new DeliveryLedger(dir).recordPending(DeliveryReceipt.builder()
            .id('orphan-p').idempotencyKey('rec-k').kind('k').destination('#')
            .status('PENDING').attemptedAt(now).build())
        def boom = new AtomicInteger(0)
        def hooked = new DeliveryLedger(dir, null, {
            if (boom.getAndIncrement() == 0) {
                throw new RuntimeException('crash after data before index')
            }
        })

        when:
        hooked.recordDelivered(DeliveryReceipt.builder()
            .id('orphan-1').idempotencyKey('rec-k').kind('k').destination('#')
            .status('DELIVERED').providerMessageId('ts-o')
            .attemptedAt(now).completedAt(now).build())

        then:
        thrown(Exception)

        when: 'new instance recovers exact record'
        def recovered = new DeliveryLedger(dir)
        def found = recovered.findDelivered('rec-k')
        def claim = recovered.tryClaimPending('rec-k', DeliveryReceipt.builder()
            .id('nope').idempotencyKey('rec-k').kind('k').destination('#')
            .status('PENDING').attemptedAt(now).build())

        then:
        found != null
        found.id == 'orphan-1'
        found.providerMessageId == 'ts-o'
        !claim.claimed
        recovered.listReceiptIds().contains('orphan-1')
        // no duplicate on second load
        new DeliveryLedger(dir).listReceiptIds().count { it == 'orphan-1' } == 1
    }

    def "decision store recovers orphan after data-before-index crash without duplicate"() {
        given:
        def dir = temp()
        def boom = new AtomicInteger(0)
        def hooked = new DecisionStore(dir, null, {
            if (boom.getAndIncrement() == 0) {
                throw new RuntimeException('crash after data before index')
            }
        })
        def now = Instant.now()
        def d = DecisionRecord.builder()
            .id('dec-orphan').proposalId('prop-o').planId('p').planVersion(1)
            .planHash('abc').action('APPROVE').status('ACCEPTED')
            .actorId('u1').correlationId('c-o').decidedAt(now).build()

        when:
        hooked.appendClassified(d)

        then:
        thrown(Exception)

        when:
        def store = new DecisionStore(dir)
        def loaded = store.load('dec-orphan')

        then:
        loaded != null
        loaded.action == 'APPROVE'
        store.listForProposal('prop-o')*.id == ['dec-orphan']
        store.listForCorrelation('c-o')*.id == ['dec-orphan']

        when: 'same identity is durable replay with new id (no second ACCEPTED)'
        def replayOut = store.appendClassified(d)

        then:
        replayOut.isIdempotentReplay()
        replayOut.persisted.id != 'dec-orphan'
        replayOut.persisted.id.startsWith('dec-replay-')
        store.listForCorrelation('c-o').count { it.isAccepted() } == 1
        store.listForCorrelation('c-o').count { it.isReplayed() } == 1
    }

    def "transition refuses UNKNOWN and NEEDS_RECONCILIATION to DELIVERED or FAILED; reconcile succeeds audited"() {
        given:
        def ledger = new DeliveryLedger(temp())
        def now = Instant.parse('2026-08-07T12:00:00Z')
        ledger.recordPending(DeliveryReceipt.builder()
            .id('p-u').idempotencyKey('rec-only').kind('k').destination('#')
            .status('PENDING').attemptedAt(now).build())
        ledger.transition('rec-only', ['PENDING'] as Set, DeliveryReceipt.builder()
            .id('u-rec').idempotencyKey('rec-only').kind('k').destination('#')
            .status('UNKNOWN').attemptedAt(now).completedAt(now).build())
        def before = ledger.findLatest('rec-only')

        when: 'direct transition UNKNOWN → DELIVERED refused, no mutation'
        Exception exDel = null
        try {
            ledger.transition('rec-only', ['UNKNOWN'] as Set, DeliveryReceipt.builder()
                .id('d-bad').idempotencyKey('rec-only').kind('k').destination('#')
                .status('DELIVERED').providerMessageId('ts')
                .attemptedAt(now).completedAt(now).build())
        } catch (Exception e) {
            exDel = e
        }

        then:
        exDel instanceof DeliveryLedger.IllegalTransitionException
        ledger.findLatest('rec-only').id == before.id
        ledger.findLatest('rec-only').status == 'UNKNOWN'
        ledger.findDelivered('rec-only') == null

        when: 'direct transition UNKNOWN → FAILED refused'
        Exception exFail = null
        try {
            ledger.transition('rec-only', ['UNKNOWN'] as Set, DeliveryReceipt.builder()
                .id('f-bad').idempotencyKey('rec-only').kind('k').destination('#')
                .status('FAILED').attemptedAt(now).completedAt(now).build())
        } catch (Exception e) {
            exFail = e
        }

        then:
        exFail instanceof DeliveryLedger.IllegalTransitionException
        ledger.findLatest('rec-only').status == 'UNKNOWN'

        when: 'NEEDS_RECONCILIATION via transition from UNKNOWN is allowed'
        ledger.transition('rec-only', ['UNKNOWN'] as Set, DeliveryReceipt.builder()
            .id('nr-1').idempotencyKey('rec-only').kind('k').destination('#')
            .status('NEEDS_RECONCILIATION').attemptedAt(now).completedAt(now).build())

        then:
        ledger.findLatest('rec-only').status == 'NEEDS_RECONCILIATION'

        when: 'NEEDS_RECONCILIATION → DELIVERED via transition refused'
        Exception exNr = null
        try {
            ledger.transition('rec-only', ['NEEDS_RECONCILIATION'] as Set, DeliveryReceipt.builder()
                .id('d-nr').idempotencyKey('rec-only').kind('k').destination('#')
                .status('DELIVERED').providerMessageId('ts')
                .attemptedAt(now).completedAt(now).build())
        } catch (Exception e) {
            exNr = e
        }

        then:
        exNr instanceof DeliveryLedger.IllegalTransitionException
        ledger.findLatest('rec-only').status == 'NEEDS_RECONCILIATION'

        when: 'reconcile succeeds with actor/reason/time audit'
        def audited = ledger.reconcile('rec-only', DeliveryReceipt.builder()
            .id('d-ok').idempotencyKey('rec-only').kind('k').destination('#')
            .status('DELIVERED').providerMessageId('ts-ok')
            .attemptedAt(now).completedAt(now).build(), 'ops confirmed delivery', 'jorsten')

        then:
        audited.status == 'DELIVERED'
        audited.metadata.reconciled == true
        audited.metadata.reconcileReason == 'ops confirmed delivery'
        audited.metadata.reconcileActor == 'jorsten'
        audited.metadata.reconcileFrom == 'NEEDS_RECONCILIATION'
        audited.metadata.reconcileAt != null
        audited.metadata.audit instanceof Map
        audited.metadata.audit.actor == 'jorsten'
        ledger.findDelivered('rec-only').id == 'd-ok'

        when: 'PENDING provider completion transitions remain legal'
        ledger.recordPending(DeliveryReceipt.builder()
            .id('p2').idempotencyKey('prov-ok').kind('k').destination('#')
            .status('PENDING').attemptedAt(now).build())
        def delivered = ledger.transition('prov-ok', ['PENDING'] as Set, DeliveryReceipt.builder()
            .id('d2').idempotencyKey('prov-ok').kind('k').destination('#')
            .status('DELIVERED').providerMessageId('ts2')
            .attemptedAt(now).completedAt(now).build())

        then:
        delivered.status == 'DELIVERED'
        ledger.wasDelivered('prov-ok')
    }

    def "false 2xx webhook body never creates delivered barrier; retry claim allowed"() {
        given:
        def dir = temp()
        def ledger = new DeliveryLedger(dir)
        def now = Instant.now()
        // Simulate MessagingService: claim pending then record FAILED from false 2xx
        def claim = ledger.tryClaimPending('false-2xx', DeliveryReceipt.builder()
            .id('pend-f').idempotencyKey('false-2xx').kind('daily_summary').destination('#p')
            .status('PENDING').attemptedAt(now).build())
        assert claim.claimed
        ledger.transition('false-2xx', ['PENDING'] as Set, DeliveryReceipt.builder()
            .id('fail-f').idempotencyKey('false-2xx').kind('daily_summary').destination('#p')
            .status('FAILED').errorClassification('WEBHOOK_BODY')
            .errorMessage('not plain ok').attemptedAt(now).completedAt(now)
            .metadata([responseClass: 'json', httpStatus: 200]).build())

        expect:
        !ledger.wasDelivered('false-2xx')
        ledger.findLatest('false-2xx').status == 'FAILED'
        !ledger.blocksResend('false-2xx')

        when:
        def retry = ledger.tryClaimPending('false-2xx', DeliveryReceipt.builder()
            .id('pend-2').idempotencyKey('false-2xx').kind('daily_summary').destination('#p')
            .status('PENDING').attemptedAt(now).build())

        then:
        retry.claimed
    }

    def "raw record is private; public API reflection cannot call it; FAILED to DELIVERED illegal"() {
        given:
        def ledger = new DeliveryLedger(temp())
        def now = Instant.now()

        expect: 'record method is private — not part of public API'
        def m = DeliveryLedger.declaredMethods.find { it.name == 'record' && it.parameterTypes.length == 1 }
        m != null
        java.lang.reflect.Modifier.isPrivate(m.modifiers)

        when: 'absent → DELIVERED without claim refused'
        ledger.recordDelivered(DeliveryReceipt.builder()
            .id('no-claim').idempotencyKey('abs-k').kind('k').destination('#')
            .status('DELIVERED').attemptedAt(now).completedAt(now).build())

        then:
        thrown(DeliveryLedger.IllegalTransitionException)
        !ledger.wasDelivered('abs-k')
        ledger.findLatest('abs-k') == null

        when: 'FAILED → DELIVERED without PENDING refused and no mutation'
        ledger.recordFailed(DeliveryReceipt.builder()
            .id('f-only').idempotencyKey('fail-k').kind('k').destination('#')
            .status('FAILED').attemptedAt(now).completedAt(now).build())
        def before = ledger.findLatest('fail-k')
        ledger.recordDelivered(DeliveryReceipt.builder()
            .id('d-sneak').idempotencyKey('fail-k').kind('k').destination('#')
            .status('DELIVERED').attemptedAt(now).completedAt(now).build())

        then:
        thrown(DeliveryLedger.IllegalTransitionException)
        ledger.findLatest('fail-k').id == before.id
        ledger.findLatest('fail-k').status == 'FAILED'
        !ledger.wasDelivered('fail-k')
    }

    def "appendClassified refuses ACCEPTED/REPLAY with blank correlation; no ACCEPTED file"() {
        given:
        def dir = temp()
        def store = new DecisionStore(dir)
        def now = Instant.parse('2026-08-07T12:00:00Z')
        def valid = DecisionRecord.builder()
            .id('dec-ok').proposalId('prop-1').planId('p').planVersion(1)
            .planHash('abc').action('APPROVE').status('ACCEPTED')
            .actorId('u1').correlationId('corr-ok').decidedAt(now).build()
        // Bypass builder with Groovy metaClass so store gate is exercised independently
        def blankCorr = DecisionRecord.builder()
            .id('dec-blank').proposalId('prop-1').planId('p').planVersion(1)
            .planHash('abc').action('APPROVE').status('ACCEPTED')
            .actorId('u1').correlationId('will-blank').decidedAt(now).build()
        blankCorr.metaClass.getCorrelationId = { -> null }
        def emptyCorr = DecisionRecord.builder()
            .id('dec-empty').proposalId('prop-1').planId('p').planVersion(1)
            .planHash('abc').action('APPLY_SAFE').status('ACCEPTED')
            .actorId('u1').correlationId('will-empty').decidedAt(now).build()
        emptyCorr.metaClass.getCorrelationId = { -> '   ' }
        def replayBlank = DecisionRecord.builder()
            .id('dec-rep').proposalId('prop-1').planId('p').planVersion(1)
            .planHash('abc').action('APPROVE').status('IDEMPOTENT_REPLAY')
            .actorId('u1').correlationId('will-rep-blank').decidedAt(now).build()
        replayBlank.metaClass.getCorrelationId = { -> null }

        when:
        store.appendClassified(blankCorr)
        then:
        def e1 = thrown(IllegalArgumentException)
        e1.message.toLowerCase().contains('correlation')
        store.load('dec-blank') == null
        store.listIds().isEmpty()
        !Files.exists(dir.resolve('decision-' +
            ApplicationStateStore.encodeKey('dec-blank') + '.json'))

        when:
        store.appendClassified(emptyCorr)
        then:
        thrown(IllegalArgumentException)
        store.load('dec-empty') == null

        when:
        store.appendClassified(replayBlank)
        then:
        thrown(IllegalArgumentException)
        store.load('dec-rep') == null

        when: 'valid correlation exact one accepted'
        def out = store.appendClassified(valid)
        then:
        out.isNewAccepted()
        store.load('dec-ok').status == 'ACCEPTED'
        store.load('dec-ok').correlationId == 'corr-ok'
        store.listForCorrelation('corr-ok').size() == 1
    }

    def "concurrent no-correlation cannot persist two ACCEPTED"() {
        given:
        def dir = temp()
        def now = Instant.parse('2026-08-07T12:00:00Z')
        def pool = Executors.newFixedThreadPool(2)
        def barrier = new java.util.concurrent.CyclicBarrier(2)
        def refused = new AtomicInteger(0)
        def accepted = new AtomicInteger(0)

        when:
        def futures = (0..<2).collect { i ->
            pool.submit {
                barrier.await(5, TimeUnit.SECONDS)
                def store = new DecisionStore(dir)
                try {
                    def candidate = DecisionRecord.builder()
                        .id("dec-nc-${i}").proposalId('prop-1').planId('p').planVersion(1)
                        .planHash('abc').action('APPROVE').status('ACCEPTED')
                        .actorId('u1').correlationId('tmp').decidedAt(now).build()
                    candidate.metaClass.getCorrelationId = { -> null }
                    def out = store.appendClassified(candidate)
                    if (out.isNewAccepted()) {
                        accepted.incrementAndGet()
                    }
                } catch (IllegalArgumentException e) {
                    refused.incrementAndGet()
                }
            }
        }
        futures.each { it.get(30, TimeUnit.SECONDS) }
        pool.shutdown()
        def all = new DecisionStore(dir).listIds()

        then:
        refused.get() == 2
        accepted.get() == 0
        all.isEmpty()
    }

    def "appendClassified concurrent same command exactly one ACCEPTED and one replay"() {
        given:
        def dir = temp()
        def now = Instant.parse('2026-08-07T12:00:00Z')
        def pool = Executors.newFixedThreadPool(2)
        def barrier = new java.util.concurrent.CyclicBarrier(2)
        def accepted = new AtomicInteger(0)
        def replayed = new AtomicInteger(0)
        def conflicts = new AtomicInteger(0)

        when:
        def futures = (0..<2).collect { i ->
            pool.submit {
                barrier.await(5, TimeUnit.SECONDS)
                def store = new DecisionStore(dir)
                def candidate = DecisionRecord.builder()
                    .id("dec-conc-${i}").proposalId('prop-1').planId('p').planVersion(1)
                    .planHash('abc').action('APPROVE').status('ACCEPTED')
                    .actorId('u1').correlationId('shared-corr').decidedAt(now).build()
                def out = store.appendClassified(candidate)
                if (out.isNewAccepted()) accepted.incrementAndGet()
                else if (out.isIdempotentReplay()) replayed.incrementAndGet()
                else if (out.isConflict()) conflicts.incrementAndGet()
            }
        }
        futures.each { it.get(30, TimeUnit.SECONDS) }
        pool.shutdown()
        def store = new DecisionStore(dir)
        def all = store.listForCorrelation('shared-corr')

        then:
        accepted.get() == 1
        replayed.get() == 1
        conflicts.get() == 0
        all.count { it.isAccepted() } == 1
        all.count { it.isReplayed() } == 1
        all.findAll { it.isAccepted() }.size() == 1
    }

    def "appendClassified concurrent conflict commands max one accepted one rejected"() {
        given:
        def dir = temp()
        def now = Instant.parse('2026-08-07T12:00:00Z')
        def pool = Executors.newFixedThreadPool(2)
        def barrier = new java.util.concurrent.CyclicBarrier(2)
        def accepted = new AtomicInteger(0)
        def conflictOut = new AtomicInteger(0)

        when:
        def futures = [
            pool.submit {
                barrier.await(5, TimeUnit.SECONDS)
                def store = new DecisionStore(dir)
                def out = store.appendClassified(DecisionRecord.builder()
                    .id('dec-a').proposalId('prop-1').planId('p').planVersion(1)
                    .planHash('abc').action('APPROVE').status('ACCEPTED')
                    .actorId('u1').correlationId('c-conflict').decidedAt(now).build())
                if (out.isNewAccepted()) accepted.incrementAndGet()
                if (out.isConflict()) conflictOut.incrementAndGet()
            },
            pool.submit {
                barrier.await(5, TimeUnit.SECONDS)
                def store = new DecisionStore(dir)
                def out = store.appendClassified(DecisionRecord.builder()
                    .id('dec-r').proposalId('prop-1').planId('p').planVersion(1)
                    .planHash('abc').action('REJECT').status('ACCEPTED')
                    .actorId('u1').correlationId('c-conflict').decidedAt(now).build())
                if (out.isNewAccepted()) accepted.incrementAndGet()
                if (out.isConflict()) conflictOut.incrementAndGet()
            }
        ]
        futures.each { it.get(30, TimeUnit.SECONDS) }
        pool.shutdown()
        def all = new DecisionStore(dir).listForCorrelation('c-conflict')

        then:
        accepted.get() == 1
        conflictOut.get() == 1
        all.count { it.isAccepted() } == 1
        all.count { it.status == 'REJECTED_REPLAY_CONFLICT' } == 1
        all.count { it.isAccepted() } <= 1
        all.findAll { it.status == 'REJECTED_REPLAY_CONFLICT' }.every {
            it.id.startsWith('dec-conflict-')
        }
        all*.id.unique().size() == all.size()
    }

    def "cross-instance same candidate id: one accepted one replay distinct durable ids"() {
        given:
        def dir = temp()
        def now = Instant.parse('2026-08-07T12:00:00Z')
        def sharedId = 'dec-forced-same'
        def corr = 'corr-multi-a'
        def mk = {
            DecisionRecord.builder()
                .id(sharedId).proposalId('prop-1').planId('p').planVersion(1)
                .planHash('abc').action('APPROVE').status('ACCEPTED')
                .actorId('u1').correlationId(corr).decidedAt(now).build()
        }

        when:
        def s1 = new DecisionStore(dir)
        def s2 = new DecisionStore(dir)
        def o1 = s1.appendClassified(mk())
        def o2 = s2.appendClassified(mk())
        def all = new DecisionStore(dir).listForCorrelation(corr)

        then:
        [o1, o2].count { it.isNewAccepted() } == 1
        [o1, o2].count { it.isIdempotentReplay() } == 1
        o1.persisted.id != o2.persisted.id
        all.count { it.isAccepted() } == 1
        all.count { it.isReplayed() } == 1
        all.find { it.isReplayed() }.id.startsWith('dec-replay-')
        all.find { it.isAccepted() }.id == (o1.isNewAccepted() ? o1.persisted.id : o2.persisted.id)
        // Approval binds actual persisted accepted id
        def accepted = all.find { it.isAccepted() }
        accepted.toApproval().id == accepted.id
    }

    def "different correlations same candidate id both accepted with distinct durable ids"() {
        given:
        def store = new DecisionStore(temp())
        def now = Instant.parse('2026-08-07T12:00:00Z')
        def sharedId = 'dec-shared-cand'
        def a = DecisionRecord.builder()
            .id(sharedId).proposalId('prop-1').planId('p').planVersion(1)
            .planHash('abc').action('APPROVE').status('ACCEPTED')
            .actorId('u1').correlationId('corr-x').decidedAt(now).build()
        def b = DecisionRecord.builder()
            .id(sharedId).proposalId('prop-1').planId('p').planVersion(1)
            .planHash('abc').action('APPROVE').status('ACCEPTED')
            .actorId('u1').correlationId('corr-y').decidedAt(now).build()

        when:
        def oa = store.appendClassified(a)
        def ob = store.appendClassified(b)

        then:
        oa.isNewAccepted()
        ob.isNewAccepted()
        oa.persisted.id == sharedId
        ob.persisted.id != sharedId
        ob.persisted.id.startsWith('dec-ok-')
        store.load(oa.persisted.id).correlationId == 'corr-x'
        store.load(ob.persisted.id).correlationId == 'corr-y'
        oa.persisted.toApproval().id == oa.persisted.id
        ob.persisted.toApproval().id == ob.persisted.id
    }

    def "conflict and replay ids are prefix-derived never nanoTime; concurrent multi-instance stable"() {
        given:
        def dir = temp()
        def now = Instant.parse('2026-08-07T12:00:00Z')
        def n = 8
        def pool = Executors.newFixedThreadPool(4)
        def latch = new CountDownLatch(1)
        def accepted = new AtomicInteger(0)
        def replayed = new AtomicInteger(0)
        def conflicts = new AtomicInteger(0)
        def errors = new AtomicInteger(0)

        when:
        def futures = (0..<n).collect { i ->
            pool.submit {
                latch.await(5, TimeUnit.SECONDS)
                try {
                    def store = new DecisionStore(dir)
                    // half same approve identity, half conflicting reject identity, forced same cand id
                    def action = (i % 2 == 0) ? 'APPROVE' : 'REJECT'
                    def out = store.appendClassified(DecisionRecord.builder()
                        .id('dec-forced-race').proposalId('prop-1').planId('p').planVersion(1)
                        .planHash('abc').action(action).status('ACCEPTED')
                        .actorId('u1').correlationId('corr-race').decidedAt(now).build())
                    if (out.isNewAccepted()) accepted.incrementAndGet()
                    else if (out.isIdempotentReplay()) replayed.incrementAndGet()
                    else if (out.isConflict()) conflicts.incrementAndGet()
                } catch (Exception e) {
                    errors.incrementAndGet()
                }
            }
        }
        latch.countDown()
        futures.each { it.get(60, TimeUnit.SECONDS) }
        pool.shutdown()
        def all = new DecisionStore(dir).listForCorrelation('corr-race')

        then:
        errors.get() == 0
        accepted.get() == 1
        all.count { it.isAccepted() } == 1
        replayed.get() + conflicts.get() == n - 1
        all.every { !it.id.matches(/.*\d{13,}.*/) || it.id.startsWith('dec-') }
        all.findAll { it.isReplayed() }.every { it.id.startsWith('dec-replay-') }
        all.findAll { it.status == 'REJECTED_REPLAY_CONFLICT' }.every {
            it.id.startsWith('dec-conflict-')
        }
        all*.id.unique().size() == all.size()
    }

    def "orphan recovery handles derived replay and conflict ids"() {
        given:
        def dir = temp()
        def now = Instant.parse('2026-08-07T12:00:00Z')
        def store = new DecisionStore(dir)
        def first = DecisionRecord.builder()
            .id('dec-base').proposalId('prop-1').planId('p').planVersion(1)
            .planHash('abc').action('APPROVE').status('ACCEPTED')
            .actorId('u1').correlationId('corr-or').decidedAt(now).build()
        store.appendClassified(first)
        def boom = new AtomicInteger(0)
        def hooked = new DecisionStore(dir, null, {
            if (boom.getAndIncrement() == 0) {
                throw new RuntimeException('crash after derived data')
            }
        })

        when:
        hooked.appendClassified(DecisionRecord.builder()
            .id('dec-base').proposalId('prop-1').planId('p').planVersion(1)
            .planHash('abc').action('APPROVE').status('ACCEPTED')
            .actorId('u1').correlationId('corr-or').decidedAt(now).build())

        then:
        thrown(Exception)

        when:
        def recovered = new DecisionStore(dir)
        def all = recovered.listForCorrelation('corr-or')
        def replay = all.find { it.isReplayed() }

        then:
        all.count { it.isAccepted() } == 1
        replay != null
        replay.id.startsWith('dec-replay-')
        recovered.load(replay.id) != null
        recovered.listIds().contains(replay.id)
    }
}
