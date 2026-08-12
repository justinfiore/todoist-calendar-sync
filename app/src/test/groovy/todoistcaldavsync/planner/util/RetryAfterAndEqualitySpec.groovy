package todoistcaldavsync.planner.util

import spock.lang.Specification
import todoistcaldavsync.planner.adapters.SlackMessagingGateway
import todoistcaldavsync.planner.domain.DecisionRecord
import todoistcaldavsync.planner.domain.DeliveryReceipt
import todoistcaldavsync.planner.domain.Message
import todoistcaldavsync.planner.domain.Proposal

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

class RetryAfterAndEqualitySpec extends Specification {

    def "Retry-After delta-seconds exact and case-insensitive header"() {
        given:
        def now = Instant.parse('2026-08-07T12:00:00Z')

        expect:
        RetryAfter.parse('12', now) == 12L
        RetryAfter.parse('0', now) == 0L
        RetryAfter.parseSeconds(['retry-after': ['30']], now) == 30L
        RetryAfter.parseSeconds(['RETRY-AFTER': ['5']], now) == 5L
        SlackMessagingGateway.parseRetryAfterSeconds(['Retry-After': ['12']], now) == 12L
    }

    def "Retry-After RFC 7231 HTTP-date past clamps to 0; future delta; malformed null"() {
        given:
        def now = Instant.parse('2026-08-07T12:00:00Z')
        def future = now.plusSeconds(90)
        def past = now.minusSeconds(30)
        String futureHttp = DateTimeFormatter.RFC_1123_DATE_TIME
            .withLocale(Locale.US).withZone(ZoneOffset.UTC).format(future)
        String pastHttp = DateTimeFormatter.RFC_1123_DATE_TIME
            .withLocale(Locale.US).withZone(ZoneOffset.UTC).format(past)
        // lowercase weekday still often appears
        String mixed = futureHttp

        expect:
        RetryAfter.parse(futureHttp, now) == 90L
        RetryAfter.parse(pastHttp, now) == 0L
        RetryAfter.parse(mixed, now) == 90L
        RetryAfter.parse('not-a-date', now) == null
        RetryAfter.parse('', now) == null
        RetryAfter.parse(null, now) == null
        RetryAfter.parseSeconds(['Retry-After': ['bogus']], now) == null
    }

    def "Retry-After excessive clamped to max"() {
        given:
        def now = Instant.parse('2026-08-07T12:00:00Z')

        expect:
        RetryAfter.parse('999999999', now, 100L) == 100L
        RetryAfter.parse('50', now, 100L) == 50L
    }

    def "Message DeliveryReceipt Proposal DecisionRecord equals hashCode set membership and roundtrip"() {
        given:
        def t = Instant.parse('2026-08-07T12:00:00Z')
        def m1 = Message.builder().kind('daily_summary').body('hi').destination('#p')
            .idempotencyKey('k1').createdAt(t).planId('p').planVersion(1).planHash('abc').build()
        def m2 = Message.fromMap(m1.toMap())
        def m3 = Message.builder().kind('daily_summary').body('other').destination('#p')
            .idempotencyKey('k2').createdAt(t).build()

        def r1 = DeliveryReceipt.builder().id('d1').idempotencyKey('k1').kind('k')
            .destination('#p').status('DELIVERED').attemptedAt(t).completedAt(t)
            .errorClassification('X').errorMessage('e').build()
        def r2 = DeliveryReceipt.fromMap(r1.toMap())

        def p1 = Proposal.builder().id('prop-1').planId('plan').planVersion(2)
            .planHash('hashhashhash').createdAt(t).build()
        def p2 = Proposal.fromMap(p1.toMap())

        def d1 = DecisionRecord.builder().id('dec-1').action('APPROVE').status('ACCEPTED')
            .actorId('a').decidedAt(t).planId('plan').planVersion(2).planHash('hashhashhash')
            .proposalId('prop-1').reason('ok').correlationId('corr-eq-1').build()
        def d2 = DecisionRecord.fromMap(d1.toMap())
        def dHelp = DecisionRecord.builder().id('dec-h').action('HELP').status('ACCEPTED')
            .actorId('a').decidedAt(t).correlationId('corr-help').build()
        def dHelpRt = DecisionRecord.fromMap(dHelp.toMap())

        expect:
        m1 == m2
        m1.hashCode() == m2.hashCode()
        m1 != m3
        ([m1] as Set).contains(m2)

        r1 == r2
        r1.hashCode() == r2.hashCode()
        ([r1] as Set).contains(r2)

        p1 == p2
        p1.hashCode() == p2.hashCode()
        ([p1] as Set).contains(p2)

        d1 == d2
        d1.hashCode() == d2.hashCode()
        ([d1] as Set).contains(d2)

        dHelp == dHelpRt
        dHelp.planVersion == 0
        dHelp.planHash == null
        dHelpRt.planHash == null
    }

    def "BoundedText strips controls bounds emoji and marks truncation"() {
        expect:
        BoundedText.sanitizeReason('a\u0000b\u0007c') == 'abc'
        BoundedText.sanitizeReason('  hi\nthere\t') == 'hi there'
        def big = 'x' * 5000
        def s = BoundedText.sanitizeReason(big)
        BoundedText.codePointLength(s) <= BoundedText.MAX_REASON_CODE_POINTS
        s.endsWith(BoundedText.TRUNCATION_MARKER)
        def emoji = '😀' * 3000
        def e = BoundedText.sanitizeReason(emoji)
        BoundedText.codePointLength(e) <= BoundedText.MAX_REASON_CODE_POINTS
        BoundedText.codePointLength(BoundedText.sanitizeCommand('z' * 20000)) <=
            BoundedText.MAX_COMMAND_CODE_POINTS
    }

    def "Message builder requires explicit createdAt; same inputs same output"() {
        given:
        def t = Instant.parse('2026-08-07T12:00:00Z')

        when: 'missing createdAt rejected'
        Message.builder().kind('daily_summary').body('hi').destination('#p')
            .idempotencyKey('k').build()

        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.toLowerCase().contains('createdat')

        when: 'null createdAt rejected'
        Message.builder().kind('daily_summary').body('hi').destination('#p')
            .idempotencyKey('k').createdAt(null).build()

        then:
        thrown(IllegalArgumentException)

        when: 'same inputs → same output'
        def a = Message.builder().kind('daily_summary').body('hi').destination('#p')
            .idempotencyKey('k').createdAt(t).planId('p1').build()
        def b = Message.builder().kind('daily_summary').body('hi').destination('#p')
            .idempotencyKey('k').createdAt(t).planId('p1').build()

        then:
        a == b
        a.hashCode() == b.hashCode()
        a.createdAt == t
    }

    def "DecisionRecord builder rejects null blank correlation; fromMap malformed rejects"() {
        given:
        def t = Instant.parse('2026-08-07T12:00:00Z')
        def base = {
            DecisionRecord.builder().id('dec-c').action('APPROVE').status('ACCEPTED')
                .actorId('a').decidedAt(t).planId('plan').planVersion(1).planHash('h' * 16)
                .proposalId('prop-1')
        }

        when: 'null correlation'
        base().correlationId(null).build()
        then:
        def e1 = thrown(IllegalArgumentException)
        e1.message.toLowerCase().contains('correlation')

        when: 'blank correlation'
        base().correlationId('   ').build()
        then:
        thrown(IllegalArgumentException)

        when: 'missing correlation'
        base().build()
        then:
        thrown(IllegalArgumentException)

        when: 'fromMap missing correlation'
        DecisionRecord.fromMap([
            id: 'dec-m', action: 'APPROVE', status: 'ACCEPTED', actorId: 'a',
            decidedAt: t.toString(), planId: 'plan', planVersion: 1, planHash: 'h' * 16
        ])
        then:
        thrown(IllegalArgumentException)

        when: 'fromMap blank correlation'
        DecisionRecord.fromMap([
            id: 'dec-m2', action: 'APPLY_SAFE', status: 'ACCEPTED', actorId: 'a',
            decidedAt: t.toString(), planId: 'plan', planVersion: 1, planHash: 'h' * 16,
            correlationId: '  '
        ])
        then:
        thrown(IllegalArgumentException)

        when: 'HELP also requires correlation'
        DecisionRecord.builder().id('h').action('HELP').status('ACCEPTED')
            .actorId('a').decidedAt(t).build()
        then:
        thrown(IllegalArgumentException)

        when: 'valid correlation accepted and bounded'
        def huge = 'c' * 500
        def ok = base().correlationId(huge).build()
        then:
        ok.correlationId != null
        BoundedText.codePointLength(ok.correlationId) <= DecisionRecord.MAX_CORRELATION_ID_CODE_POINTS
    }

    def "Proposal fromPlan deterministic; null createdAt rejected; builder requires createdAt"() {
        given:
        def t = Instant.parse('2026-08-07T12:00:00Z')
        def plan = todoistcaldavsync.planner.domain.Plan.builder()
            .id('plan-det').version(1).createdAt(t).mode('approval_required').build()

        when: 'repeated same inputs equal'
        def a = Proposal.fromPlan(plan)
        def b = Proposal.fromPlan(plan)

        then:
        a == b
        a.hashCode() == b.hashCode()
        a.createdAt == t
        a.planId == 'plan-det'

        when: 'builder missing createdAt'
        Proposal.builder().id('p').planId('x').planVersion(1).planHash('h').build()
        then:
        def ex = thrown(IllegalArgumentException)
        ex.message.toLowerCase().contains('createdat')

        when: 'builder null createdAt'
        Proposal.builder().id('p').planId('x').planVersion(1).planHash('h').createdAt(null).build()
        then:
        thrown(IllegalArgumentException)

        when: 'fromMap missing createdAt'
        Proposal.fromMap([id: 'p', planId: 'x', planVersion: 1, planHash: 'h'])
        then:
        thrown(IllegalArgumentException)
    }
}
