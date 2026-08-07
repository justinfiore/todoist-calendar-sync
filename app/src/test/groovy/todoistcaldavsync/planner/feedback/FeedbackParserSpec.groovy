package todoistcaldavsync.planner.feedback

import spock.lang.Specification
import todoistcaldavsync.planner.domain.Plan
import todoistcaldavsync.planner.domain.PlanHash
import todoistcaldavsync.planner.domain.Proposal
import todoistcaldavsync.planner.domain.ScheduledBlock
import todoistcaldavsync.planner.domain.Task
import todoistcaldavsync.planner.state.DecisionStore

import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class FeedbackParserSpec extends Specification {

    def dirs = []
    Instant now = Instant.parse('2026-08-07T15:00:00Z')

    def cleanup() {
        dirs.each {
            Files.walk(it).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    private DecisionStore store() {
        def d = Files.createTempDirectory('dec-')
        dirs << d
        new DecisionStore(d)
    }

    private Plan plan() {
        def t = Task.builder().id('t1').content('Work').priority(1)
            .effectiveDuration(Duration.ofMinutes(30)).durationSource('t').build()
        def start = Instant.parse('2026-08-07T16:00:00Z')
        def block = ScheduledBlock.builder().id('b1').start(start).end(start + Duration.ofMinutes(30))
            .taskIds(['t1']).title('Work').reason('fit').build()
        Plan.builder().id('plan-fb').version(3).createdAt(now).mode('approval_required')
            .tasks([t]).scheduledBlocks([block]).build()
    }

    private FeedbackParser.FeedbackContext ctx(Plan p, Map extra = [:]) {
        Map base = [
            actorId      : 'jorsten',
            correlationId: 'corr-1',
            destination  : '#planner',
            plan         : p
        ]
        if (extra) {
            base.putAll(extra)
        }
        new FeedbackParser.FeedbackContext(base)
    }

    def "approve happy path converts to Phase 3 Approval"() {
        given:
        def p = plan()
        def hash = PlanHash.compute(p)
        def prop = Proposal.fromPlan(p)
        def parser = new FeedbackParser(store(), { true }, { now })

        when:
        def result = parser.parseAndRecord(
            "approve ${prop.id} ${hash.substring(0, 12)}", ctx(p))

        then:
        result.accepted
        result.decision.action == 'APPROVE'
        result.decision.status == 'ACCEPTED'
        result.approval != null
        result.approval.planId == p.id
        result.approval.planVersion == p.version
        result.approval.planHash == hash
        result.approval.approvedBy == 'jorsten'
    }

    def "reject and apply-safe happy paths"() {
        given:
        def p = plan()
        def hash = PlanHash.compute(p)
        def prop = Proposal.fromPlan(p)
        def ds = store()
        def parser = new FeedbackParser(ds, { true }, { now })

        when:
        def rej = parser.parseAndRecord(
            "reject ${prop.id} ${hash} not today", ctx(p, [correlationId: 'c-rej']))
        def app = parser.parseAndRecord(
            "apply-safe ${prop.id} ${hash.substring(0, 16)}", ctx(p, [correlationId: 'c-safe']))

        then:
        rej.accepted
        rej.decision.action == 'REJECT'
        rej.decision.reason.contains('not today')
        rej.approval == null
        app.accepted
        app.decision.action == 'APPLY_SAFE'
        app.approval == null
    }

    def "malformed empty and unrecognized rejected"() {
        given:
        def parser = new FeedbackParser(store(), { true }, { now })
        def p = plan()

        expect:
        def r1 = parser.parseAndRecord('', ctx(p))
        def r2 = parser.parseAndRecord('please do the thing', ctx(p))
        def r3 = parser.parseAndRecord('approve only-one-arg', ctx(p))
        !r1.accepted
        r1.decision.status == 'REJECTED_MALFORMED'
        !r2.accepted
        r2.decision.status == 'REJECTED_MALFORMED'
        !r3.accepted
    }

    def "wrong proposal id and hash rejected as identity/stale"() {
        given:
        def p = plan()
        def hash = PlanHash.compute(p)
        def prop = Proposal.fromPlan(p)
        def parser = new FeedbackParser(store(), { true }, { now })

        when:
        def wrongId = parser.parseAndRecord("approve prop-other ${hash.substring(0, 12)}", ctx(p))
        def wrongHash = parser.parseAndRecord("approve ${prop.id} deadbeefcafe", ctx(p))
        def shortHash = parser.parseAndRecord("approve ${prop.id} abcd", ctx(p))

        then:
        wrongId.decision.status == 'REJECTED_WRONG_IDENTITY'
        wrongHash.decision.status == 'REJECTED_STALE'
        !shortHash.accepted
    }

    def "unauthorized actor rejected"() {
        given:
        def p = plan()
        def hash = PlanHash.compute(p)
        def prop = Proposal.fromPlan(p)
        def parser = new FeedbackParser(store(), { String a -> a == 'admin' }, { now })

        when:
        def r = parser.parseAndRecord(
            "approve ${prop.id} ${hash.substring(0, 12)}",
            ctx(p, [actorId: 'intruder']))

        then:
        r.decision.status == 'REJECTED_UNAUTHORIZED'
    }

    def "idempotent replay of same correlation+action"() {
        given:
        def p = plan()
        def hash = PlanHash.compute(p)
        def prop = Proposal.fromPlan(p)
        def parser = new FeedbackParser(store(), { true }, { now })
        def c = ctx(p, [correlationId: 'same-corr'])

        when:
        def first = parser.parseAndRecord("approve ${prop.id} ${hash.substring(0, 12)}", c)
        def second = parser.parseAndRecord("approve ${prop.id} ${hash.substring(0, 12)}", c)

        then:
        first.accepted
        first.decision.status == 'ACCEPTED'
        first.approval != null
        // Replay never authorizes: accepted=false, approval=null, replayed=true
        !second.accepted
        second.replayed
        second.approval == null
        second.decision.status == 'IDEMPOTENT_REPLAY'
        second.decision.previousDecisionId == first.decision.id
        // Host anti-pattern cannot apply
        !(second.accepted || second.approval)
        // toApproval on replay decision is also null
        second.decision.toApproval() == null
    }

    def "host anti-pattern if accepted or approval cannot apply on replay; spy one total"() {
        given:
        def p = plan()
        def hash = PlanHash.compute(p)
        def prop = Proposal.fromPlan(p)
        def ds = store()
        def parser = new FeedbackParser(ds, { true }, { now })
        def c = ctx(p, [correlationId: 'anti-pat'])
        def applyCalls = new AtomicInteger(0)
        def hostApply = { FeedbackParser.FeedbackResult r ->
            // Anti-pattern hosts might write: if (accepted || approval) apply(...)
            if (r.accepted || r.approval) {
                applyCalls.incrementAndGet()
            }
        }

        when:
        def first = parser.parseAndRecord("approve ${prop.id} ${hash.substring(0, 12)}", c)
        hostApply(first)
        def second = parser.parseAndRecord("approve ${prop.id} ${hash.substring(0, 12)}", c)
        hostApply(second)

        then:
        first.accepted
        first.approval != null
        !second.accepted
        second.approval == null
        second.replayed
        applyCalls.get() == 1
        ds.listForCorrelation('anti-pat').size() == 2
    }

    def "deterministic missing correlation from actor command destination message"() {
        given:
        def p = plan()
        def hash = PlanHash.compute(p)
        def prop = Proposal.fromPlan(p)
        def parser = new FeedbackParser(store(), { true }, { now })
        def cmd = "approve ${prop.id} ${hash.substring(0, 12)}"
        def base = [actorId: 'alice', destination: '#p', threadId: 'th-1', plan: p]

        when: 'same inputs → same correlation'
        def c1 = new FeedbackParser.FeedbackContext(base)
        def c2 = new FeedbackParser.FeedbackContext(base)
        def r1 = parser.parseAndRecord(cmd, c1)
        // different store path for isolation of correlation derivation unit
        def derived1 = FeedbackParser.deriveCorrelationId('alice', cmd,
            new FeedbackParser.FeedbackContext(base))
        def derived2 = FeedbackParser.deriveCorrelationId('alice', cmd,
            new FeedbackParser.FeedbackContext(base))
        def derivedOtherActor = FeedbackParser.deriveCorrelationId('bob', cmd,
            new FeedbackParser.FeedbackContext(base + [actorId: 'bob']))
        def derivedOtherCmd = FeedbackParser.deriveCorrelationId('alice', 'help',
            new FeedbackParser.FeedbackContext(base))
        def withMsg = FeedbackParser.deriveCorrelationId('alice', cmd,
            new FeedbackParser.FeedbackContext(base + [messageId: 'msg-99']))
        def withMsgRetry = FeedbackParser.deriveCorrelationId('alice', 'different command text',
            new FeedbackParser.FeedbackContext(base + [messageId: 'msg-99']))

        then:
        derived1 == derived2
        derived1.startsWith('corr-')
        derived1 != derivedOtherActor
        derived1 != derivedOtherCmd
        withMsg == withMsgRetry // platform message id preferred; command ignored when messageId set
        withMsg != derived1
        !(derived1 ==~ /corr-\d{10,}/) // not epoch millis
        r1.decision.correlationId == derived1
    }

    def "conflicting replay rejected"() {
        given:
        def p = plan()
        def hash = PlanHash.compute(p)
        def prop = Proposal.fromPlan(p)
        def parser = new FeedbackParser(store(), { true }, { now })
        def c = ctx(p, [correlationId: 'conflict-corr'])

        when:
        parser.parseAndRecord("approve ${prop.id} ${hash.substring(0, 12)}", c)
        def conflict = parser.parseAndRecord("reject ${prop.id} ${hash.substring(0, 12)} no", c)

        then:
        conflict.decision.status == 'REJECTED_REPLAY_CONFLICT'
    }

    def "stale missing plan rejected"() {
        given:
        def parser = new FeedbackParser(store(), { true }, { now })
        def c = new FeedbackParser.FeedbackContext(actorId: 'j', correlationId: 'x', plan: null)

        when:
        def r = parser.parseAndRecord('approve prop-x abcdef012345', c)

        then:
        r.decision.status == 'REJECTED_STALE'
    }

    def "help and status do not require hash"() {
        given:
        def p = plan()
        def prop = Proposal.fromPlan(p)
        def parser = new FeedbackParser(store(), { true }, { now })

        when:
        def help = parser.parseAndRecord('help', ctx(p, [correlationId: 'h']))
        def status = parser.parseAndRecord("status ${prop.id}", ctx(p, [correlationId: 's']))

        then:
        help.accepted
        help.decision.action == 'HELP'
        status.accepted
        status.decision.action == 'STATUS'
    }

    def "parser never invokes plan applier side effects"() {
        given:
        def applyCalls = new AtomicInteger(0)
        // FeedbackParser has no PlanApplier dependency — structural guarantee
        def parser = new FeedbackParser(store(), { true }, { now })
        def p = plan()
        def hash = PlanHash.compute(p)
        def prop = Proposal.fromPlan(p)

        when:
        parser.parseAndRecord("approve ${prop.id} ${hash.substring(0, 12)}", ctx(p))
        parser.parseAndRecord("apply-safe ${prop.id} ${hash.substring(0, 12)}",
            ctx(p, [correlationId: 'other']))
        parser.parseAndRecord("reject ${prop.id} ${hash.substring(0, 12)} no",
            ctx(p, [correlationId: 'other2']))

        then:
        applyCalls.get() == 0
        parser.class.declaredFields.every { it.type.simpleName != 'PlanApplier' }
    }

    def "decision store is append-only for duplicate ids"() {
        given:
        def ds = store()
        def p = plan()
        def hash = PlanHash.compute(p)
        def prop = Proposal.fromPlan(p)
        def parser = new FeedbackParser(ds, { true }, { now })
        def r = parser.parseAndRecord("approve ${prop.id} ${hash.substring(0, 12)}", ctx(p))

        when: 'same ACCEPTED identity → durable IDEMPOTENT_REPLAY with new id (never second ACCEPTED)'
        def out = ds.appendClassified(r.decision)

        then:
        out.isIdempotentReplay()
        out.persisted.id != r.decision.id
        out.persisted.id.startsWith('dec-replay-')
        ds.listForCorrelation(r.decision.correlationId).count { it.isAccepted() } == 1
        ds.load(r.decision.id).status == 'ACCEPTED'
    }

    def "hash prefix minimum length enforced"() {
        expect:
        !FeedbackParser.hashMatches('abcdef0123456789', 'abcd')
        FeedbackParser.hashMatches('abcdef0123456789', 'abcdef01')
        FeedbackParser.hashMatches('abcdef0123456789', 'abcdef0123456789')
    }

    def "trailing or embedded command verb is REJECTED_AMBIGUOUS with zero side effects"() {
        given:
        def p = plan()
        def hash = PlanHash.compute(p)
        def prop = Proposal.fromPlan(p)
        def ds = store()
        def applyCalls = new AtomicInteger(0)
        def parser = new FeedbackParser(ds, { true }, { now })
        // Capture decision count before; ambiguous reject still appends one REJECTED_* record
        // but must not ACCEPTED-apply and PlanApplier is never wired.

        when:
        def multi = parser.parseAndRecord(
            "approve ${prop.id} ${hash.substring(0, 12)} reject ${prop.id} ${hash.substring(0, 12)}",
            ctx(p, [correlationId: 'amb-1']))
        def trailing = parser.parseAndRecord(
            "approve ${prop.id} ${hash.substring(0, 12)} reject",
            ctx(p, [correlationId: 'amb-2']))
        def reverse = parser.parseAndRecord(
            "reject ${prop.id} ${hash.substring(0, 12)} approve ${prop.id} ${hash.substring(0, 12)}",
            ctx(p, [correlationId: 'amb-3']))
        def reasonVerb = parser.parseAndRecord(
            "reject ${prop.id} ${hash.substring(0, 12)} please approve later",
            ctx(p, [correlationId: 'amb-4']))
        def punct = parser.parseAndRecord(
            "approve ${prop.id} ${hash.substring(0, 12)} reject;",
            ctx(p, [correlationId: 'amb-5']))
        def newlineVerb = parser.parseAndRecord(
            "approve ${prop.id} ${hash.substring(0, 12)}\nreject",
            ctx(p, [correlationId: 'amb-6']))
        def caseVerb = parser.parseAndRecord(
            "approve ${prop.id} ${hash.substring(0, 12)} REJECT",
            ctx(p, [correlationId: 'amb-7']))

        then:
        [multi, trailing, reverse, reasonVerb, punct, newlineVerb, caseVerb].every {
            !it.accepted && it.decision.status == 'REJECTED_AMBIGUOUS' && it.approval == null
        }
        // No ACCEPTED decisions stored for any ambiguous correlation
        ['amb-1', 'amb-2', 'amb-3', 'amb-4', 'amb-5', 'amb-6', 'amb-7'].every { corr ->
            ds.listForCorrelation(corr).every { !it.isAccepted() }
        }
        applyCalls.get() == 0
        parser.class.declaredFields.every { it.type.simpleName != 'PlanApplier' }
    }

    def "reject reason without command verbs accepted; substrings are not verbs"() {
        given:
        def p = plan()
        def hash = PlanHash.compute(p)
        def prop = Proposal.fromPlan(p)
        def ds = store()
        def parser = new FeedbackParser(ds, { true }, { now })

        when:
        def plain = parser.parseAndRecord(
            "reject ${prop.id} ${hash} not today — capacity issue",
            ctx(p, [correlationId: 'rej-plain']))
        def substr = parser.parseAndRecord(
            "reject ${prop.id} ${hash} already approved helpful on statuspage",
            ctx(p, [correlationId: 'rej-sub']))

        then:
        plain.accepted
        plain.decision.action == 'REJECT'
        plain.decision.status == 'ACCEPTED'
        plain.decision.reason.contains('not today')
        substr.accepted
        substr.decision.action == 'REJECT'
        substr.decision.status == 'ACCEPTED'
        substr.decision.reason.toLowerCase().contains('approved')
        substr.decision.reason.toLowerCase().contains('helpful')
        substr.decision.reason.toLowerCase().contains('statuspage')
        ds.listForCorrelation('rej-plain').any { it.isAccepted() }
        ds.listForCorrelation('rej-sub').any { it.isAccepted() }
    }

    def "reserved verb as standalone reason token is ambiguous; quote or avoid"() {
        given:
        def p = plan()
        def hash = PlanHash.compute(p)
        def prop = Proposal.fromPlan(p)
        def parser = new FeedbackParser(store(), { true }, { now })

        when:
        // Unquoted standalone reserved verb in reason → REJECTED_AMBIGUOUS (structured safety)
        def bare = parser.parseAndRecord(
            "reject ${prop.id} ${hash} approve",
            ctx(p, [correlationId: 'verb-bare']))
        // Quoted/escaped form is a single token that is not exactly the verb after strip
        def quoted = parser.parseAndRecord(
            "reject ${prop.id} ${hash} \"approve-later-please\"",
            ctx(p, [correlationId: 'verb-quoted']))

        then:
        !bare.accepted
        bare.decision.status == 'REJECTED_AMBIGUOUS'
        // quoted token strips edge quotes → still 'approve-later-please' which is not a verb
        quoted.accepted
        quoted.decision.action == 'REJECT'
    }

    def "command verb token detection is boundary-safe"() {
        expect:
        FeedbackParser.isCommandVerbToken('approve')
        FeedbackParser.isCommandVerbToken('REJECT')
        FeedbackParser.isCommandVerbToken('apply_safe')
        FeedbackParser.isCommandVerbToken('apply-safe')
        FeedbackParser.isCommandVerbToken(';reject;')
        !FeedbackParser.isCommandVerbToken('approved')
        !FeedbackParser.isCommandVerbToken('helpful')
        !FeedbackParser.isCommandVerbToken('statuspage')
        !FeedbackParser.isCommandVerbToken('rejections')
        !FeedbackParser.isCommandVerbToken('approval')
    }

    def "default and null authorization policy deny all including help"() {
        given:
        def p = plan()
        def hash = PlanHash.compute(p)
        def prop = Proposal.fromPlan(p)
        def ds1 = store()
        def ds2 = store()
        def ds3 = store()
        def noArg = new FeedbackParser(ds1)
        def nullPolicy = new FeedbackParser(ds2, null, { now })
        def defaultDeny = new FeedbackParser(ds3, FeedbackParser.DENY_ALL, { now })

        when:
        def rHelp = noArg.parseAndRecord('help', ctx(p, [correlationId: 'h1']))
        def rApprove = nullPolicy.parseAndRecord(
            "approve ${prop.id} ${hash.substring(0, 12)}", ctx(p, [correlationId: 'a1']))
        def rStatus = defaultDeny.parseAndRecord(
            "status ${prop.id}", ctx(p, [correlationId: 's1']))
        def rBlank = noArg.parseAndRecord('help',
            new FeedbackParser.FeedbackContext(actorId: '  ', correlationId: 'b1', plan: p))
        def rNullActor = noArg.parseAndRecord('help',
            new FeedbackParser.FeedbackContext(actorId: null, correlationId: 'n1', plan: p))

        then:
        [rHelp, rApprove, rStatus, rBlank, rNullActor].every {
            !it.accepted && it.decision.status == 'REJECTED_UNAUTHORIZED' && it.approval == null
        }
        // No accepted decisions stored
        ds1.listIds().every { id -> !ds1.load(id).isAccepted() }
        ds2.listIds().every { id -> !ds2.load(id).isAccepted() }
    }

    def "explicit allowlist allows exact actor only"() {
        given:
        def p = plan()
        def hash = PlanHash.compute(p)
        def prop = Proposal.fromPlan(p)
        def ds = store()
        def parser = new FeedbackParser(ds, FeedbackParser.allowlist(['jorsten']), { now })

        when:
        def ok = parser.parseAndRecord(
            "approve ${prop.id} ${hash.substring(0, 12)}", ctx(p, [correlationId: 'ok']))
        def denied = parser.parseAndRecord(
            "approve ${prop.id} ${hash.substring(0, 12)}",
            ctx(p, [actorId: 'intruder', correlationId: 'no']))
        def helpOk = parser.parseAndRecord('help', ctx(p, [correlationId: 'hok']))

        then:
        ok.accepted
        ok.decision.actorId == 'jorsten'
        !denied.accepted
        denied.decision.status == 'REJECTED_UNAUTHORIZED'
        helpOk.accepted
        helpOk.decision.action == 'HELP'
    }

    def "HELP and STATUS do not forge planVersion=1 or hash none"() {
        given:
        def ds = store()
        def parser = new FeedbackParser(ds, { true }, { now })
        // Distinct correlations: different actions on same correlation would conflict
        def noPlanHelp = new FeedbackParser.FeedbackContext(
            actorId: 'jorsten', correlationId: 'np-help', plan: null)
        def noPlanStatus = new FeedbackParser.FeedbackContext(
            actorId: 'jorsten', correlationId: 'np-status', plan: null)

        when:
        def help = parser.parseAndRecord('help', noPlanHelp)
        def status = parser.parseAndRecord('status prop-x', noPlanStatus)
        def helpMap = help.decision.toMap()
        def helpRt = todoistcaldavsync.planner.domain.DecisionRecord.fromMap(helpMap)

        then:
        help.accepted
        help.decision.action == 'HELP'
        help.decision.planVersion == 0
        help.decision.planHash == null
        help.decision.planId == null
        status.accepted
        status.decision.action == 'STATUS'
        status.decision.planVersion == 0
        status.decision.planHash == null
        helpRt == help.decision
        helpRt.planHash == null
        helpRt.planVersion == 0
    }

    def "HELP with loaded plan keeps optional real identity not forged none"() {
        given:
        def p = plan()
        def hash = PlanHash.compute(p)
        def parser = new FeedbackParser(store(), { true }, { now })

        when:
        def help = parser.parseAndRecord('help', ctx(p, [correlationId: 'hp']))

        then:
        help.decision.planId == p.id
        help.decision.planVersion == p.version
        help.decision.planHash == hash
        help.decision.planHash != 'none'
    }

    def "reason and command inputs are bounded and control chars stripped"() {
        given:
        def p = plan()
        def hash = PlanHash.compute(p)
        def prop = Proposal.fromPlan(p)
        def parser = new FeedbackParser(store(), { true }, { now })
        String huge = 'x' * 5000
        String controls = "reject ${prop.id} ${hash} bad\u0000\u0007reason\u001Fok"
        String emoji = "reject ${prop.id} ${hash} " + ('😀' * 3000)

        when:
        def rHuge = parser.parseAndRecord(
            "reject ${prop.id} ${hash} ${huge}", ctx(p, [correlationId: 'huge']))
        def rCtrl = parser.parseAndRecord(controls, ctx(p, [correlationId: 'ctrl']))
        def rEmoji = parser.parseAndRecord(emoji, ctx(p, [correlationId: 'emo']))
        def direct = todoistcaldavsync.planner.domain.DecisionRecord.builder()
            .id('dec-direct').action('REJECT').status('ACCEPTED')
            .actorId('a').decidedAt(now).planId(p.id).planVersion(p.version).planHash(hash)
            .correlationId('corr-direct-reason').reason('y' * 5000 + '\u0001').build()

        then:
        rHuge.accepted
        todoistcaldavsync.planner.util.BoundedText.codePointLength(rHuge.decision.reason) <=
            todoistcaldavsync.planner.util.BoundedText.MAX_REASON_CODE_POINTS
        rHuge.decision.reason.contains(todoistcaldavsync.planner.util.BoundedText.TRUNCATION_MARKER)
        rCtrl.accepted
        !rCtrl.decision.reason.contains('\u0000')
        !rCtrl.decision.reason.contains('\u0007')
        rCtrl.decision.reason.contains('bad')
        rCtrl.decision.reason.contains('reason')
        rEmoji.accepted
        todoistcaldavsync.planner.util.BoundedText.codePointLength(rEmoji.decision.reason) <=
            todoistcaldavsync.planner.util.BoundedText.MAX_REASON_CODE_POINTS
        todoistcaldavsync.planner.util.BoundedText.codePointLength(direct.reason) <=
            todoistcaldavsync.planner.util.BoundedText.MAX_REASON_CODE_POINTS
        !direct.reason.contains('\u0001')
    }

    def "oversized command input rejected or truncated before accept bypass"() {
        given:
        def parser = new FeedbackParser(store(), { true }, { now })
        def p = plan()
        // Over 8KiB of junk after a non-command start → malformed after bound
        String huge = 'please ' + ('z' * 20000)

        when:
        def r = parser.parseAndRecord(huge, ctx(p))

        then:
        !r.accepted
        r.decision.status == 'REJECTED_MALFORMED'
    }

    def "concurrent two-thread same approve: exactly one accepted one replay; host spy once"() {
        given:
        def p = plan()
        def hash = PlanHash.compute(p)
        def prop = Proposal.fromPlan(p)
        def ds = store()
        def cmd = "approve ${prop.id} ${hash.substring(0, 12)}"
        def pool = Executors.newFixedThreadPool(2)
        def barrier = new CyclicBarrier(2)
        def accepted = new AtomicInteger(0)
        def replayed = new AtomicInteger(0)
        def approvals = Collections.synchronizedList([])
        def applyCalls = new AtomicInteger(0)

        when:
        def futures = (0..<2).collect { i ->
            pool.submit {
                barrier.await(5, TimeUnit.SECONDS)
                def parser = new FeedbackParser(ds, { true }, { now })
                def r = parser.parseAndRecord(cmd, ctx(p, [correlationId: 'conc-approve']))
                if (r.accepted) accepted.incrementAndGet()
                if (r.replayed) replayed.incrementAndGet()
                if (r.approval != null) approvals << r.approval
                if (r.accepted || r.approval) applyCalls.incrementAndGet()
            }
        }
        futures.each { it.get(30, TimeUnit.SECONDS) }
        pool.shutdown()
        def stored = ds.listForCorrelation('conc-approve')

        then:
        accepted.get() == 1
        replayed.get() == 1
        approvals.size() == 1
        applyCalls.get() == 1
        stored.count { it.isAccepted() } == 1
        stored.count { it.isReplayed() } == 1
        stored.every { it.isAccepted() ? !it.isReplayed() : true }
        // isAccepted never true for replay
        stored.findAll { it.isReplayed() }.every { !it.isAccepted() }
    }

    def "concurrent conflict approve vs reject: max one accepted never two"() {
        given:
        def p = plan()
        def hash = PlanHash.compute(p)
        def prop = Proposal.fromPlan(p)
        def ds = store()
        def pool = Executors.newFixedThreadPool(2)
        def barrier = new CyclicBarrier(2)
        def accepted = new AtomicInteger(0)
        def rejectedConflict = new AtomicInteger(0)

        when:
        def futures = [
            pool.submit {
                barrier.await(5, TimeUnit.SECONDS)
                def r = new FeedbackParser(ds, { true }, { now }).parseAndRecord(
                    "approve ${prop.id} ${hash.substring(0, 12)}",
                    ctx(p, [correlationId: 'conc-conflict']))
                if (r.accepted) accepted.incrementAndGet()
                if (r.decision?.status == 'REJECTED_REPLAY_CONFLICT') rejectedConflict.incrementAndGet()
            },
            pool.submit {
                barrier.await(5, TimeUnit.SECONDS)
                def r = new FeedbackParser(ds, { true }, { now }).parseAndRecord(
                    "reject ${prop.id} ${hash.substring(0, 12)} no",
                    ctx(p, [correlationId: 'conc-conflict']))
                if (r.accepted) accepted.incrementAndGet()
                if (r.decision?.status == 'REJECTED_REPLAY_CONFLICT') rejectedConflict.incrementAndGet()
            }
        ]
        futures.each { it.get(30, TimeUnit.SECONDS) }
        pool.shutdown()
        def stored = ds.listForCorrelation('conc-conflict')

        then:
        accepted.get() == 1
        rejectedConflict.get() == 1
        stored.count { it.isAccepted() } == 1
        stored.count { it.status == 'REJECTED_REPLAY_CONFLICT' } == 1
    }

    def "DecisionRecord isAccepted only ACCEPTED; isReplayed for IDEMPOTENT_REPLAY"() {
        given:
        def t = now
        def acc = todoistcaldavsync.planner.domain.DecisionRecord.builder()
            .id('a').action('APPROVE').status('ACCEPTED').actorId('u').decidedAt(t)
            .planId('p').planVersion(1).planHash('h' * 16).correlationId('corr-acc').build()
        def rep = todoistcaldavsync.planner.domain.DecisionRecord.builder()
            .id('r').action('APPROVE').status('IDEMPOTENT_REPLAY').actorId('u').decidedAt(t)
            .planId('p').planVersion(1).planHash('h' * 16).correlationId('corr-rep').build()

        expect:
        acc.isAccepted()
        acc.isExactAccepted()
        !acc.isReplayed()
        !rep.isAccepted()
        !rep.isExactAccepted()
        rep.isReplayed()
        rep.isIdempotentReplay()
        rep.toApproval() == null
    }

    def "inferActionFromStatus is explicit status map; helpful/statuspage never HELP/STATUS"() {
        expect:
        FeedbackParser.inferActionFromStatus('REJECTED_MALFORMED', null) == 'REJECT'
        FeedbackParser.inferActionFromStatus('REJECTED_UNAUTHORIZED', null) == 'REJECT'
        FeedbackParser.inferActionFromStatus('REJECTED_STALE', null) == 'REJECT'
        FeedbackParser.inferActionFromStatus('REJECTED_AMBIGUOUS', null) == 'REJECT'
        FeedbackParser.inferActionFromStatus('REJECTED_REPLAY_CONFLICT', null) == 'REJECT'
        FeedbackParser.inferActionFromStatus('REJECTED_WRONG_IDENTITY', null) == 'REJECT'
        FeedbackParser.inferActionFromStatus(null, null) == 'REJECT'
        // explicit action wins
        FeedbackParser.inferActionFromStatus('REJECTED_MALFORMED', 'HELP') == 'HELP'
        FeedbackParser.inferActionFromStatus('REJECTED_MALFORMED', 'STATUS') == 'STATUS'
        FeedbackParser.inferActionFromStatus('REJECTED_MALFORMED', 'APPROVE') == 'APPROVE'
        // free-text words must not be treated as action mapping inputs
        FeedbackParser.inferActionFromStatus('REJECTED_MALFORMED', 'helpful') == 'REJECT'
        FeedbackParser.inferActionFromStatus('REJECTED_MALFORMED', 'statuspage') == 'REJECT'
        FeedbackParser.inferActionFromStatus('REJECTED_MALFORMED', 'was helpful on statuspage') == 'REJECT'
    }

    def "two parser instances frozen clock same command: one accepted one durable replay"() {
        given:
        def dir = Files.createTempDirectory('dec-multi-')
        dirs << dir
        def p = plan()
        def hash = PlanHash.compute(p)
        def prop = Proposal.fromPlan(p)
        def ds1 = new DecisionStore(dir)
        def ds2 = new DecisionStore(dir)
        def parser1 = new FeedbackParser(ds1, { true }, { now })
        def parser2 = new FeedbackParser(ds2, { true }, { now })
        def cmd = "approve ${prop.id} ${hash.substring(0, 12)}"
        def c = ctx(p, [correlationId: 'multi-inst-corr'])

        when:
        def r1 = parser1.parseAndRecord(cmd, c)
        def r2 = parser2.parseAndRecord(cmd, c)
        def all = new DecisionStore(dir).listForCorrelation('multi-inst-corr')

        then:
        [r1, r2].count { it.accepted } == 1
        [r1, r2].count { it.replayed } == 1
        r1.decision.id != r2.decision.id
        all.count { it.isAccepted() } == 1
        all.count { it.isReplayed() } == 1
        def accepted = [r1, r2].find { it.accepted }
        accepted.approval != null
        accepted.approval.id == accepted.decision.id
        [r1, r2].find { it.replayed }.approval == null
        all.find { it.isReplayed() }.id.startsWith('dec-replay-')
    }

    def "rejected audit records with helpful/statuspage reason stay REJECT action"() {
        given:
        def ds = store()
        def parser = new FeedbackParser(ds, { true }, { now })

        when:
        def r = parser.parseAndRecord(
            'not a command but helpful statuspage text',
            ctx(null, [correlationId: 'rej-words', actorId: 'jorsten']))

        then:
        !r.accepted
        r.decision.status == 'REJECTED_MALFORMED'
        r.decision.action == 'REJECT'
        r.decision.reason.toLowerCase().contains('helpful') ||
            r.decision.reason.toLowerCase().contains('unrecognized')
    }
}
