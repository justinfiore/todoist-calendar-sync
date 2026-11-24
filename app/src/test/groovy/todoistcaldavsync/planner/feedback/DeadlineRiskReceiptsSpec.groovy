package todoistcaldavsync.planner.feedback

import spock.lang.Specification
import java.nio.file.Files
import java.util.concurrent.Executors

class DeadlineRiskReceiptsSpec extends Specification {
    def 'revision metadata and outcomes survive saves and independent updates'() {
        given:
        def dir = Files.createTempDirectory('receipts')
        def intent = [prior: [channel: 'C', thread: 'T', proposal: 'P', plan: [id: 'old', version: 3]],
                      prepared: [planId: 'new', message: [text: 'hello', thread: 'T']], publication: [state: 'prepared']]
        when:
        DeadlineRiskReceipts.handle(dir, 'save', 'event', [[commandId: 'a', state: 'verified']])
        DeadlineRiskReceipts.handle(dir, 'merge-record', 'event', [[intent: intent]])
        DeadlineRiskReceipts.handle(dir, 'merge-record', 'event', [[intent: [publication: [state: 'published', ts: '123']]]])
        DeadlineRiskReceipts.handle(dir, 'save', 'event', [[commandId: 'b', state: 'unknown']])
        def record = DeadlineRiskReceipts.handle(dir, 'load-record', 'event')
        then:
        record.intent.prior == intent.prior
        record.intent.prepared == intent.prepared
        record.intent.publication == [state: 'published', ts: '123']
        record.outcomes == [[commandId: 'b', state: 'unknown']]
        DeadlineRiskReceipts.handle(dir, 'load', 'event') == [] as Set
        cleanup:
        dir?.toFile()?.deleteDir()
    }

    def 'clarification resolution clears pending fields without losing intent'() {
        given:
        def dir = Files.createTempDirectory('receipts')
        when:
        DeadlineRiskReceipts.handle(dir, 'save', 'event', [[pending: true, originalReply: 'reply', replyTime: 'time', proposalId: 'p', clarification: 'question']])
        DeadlineRiskReceipts.handle(dir, 'merge-record', 'event', [[intent: [planId: 'new']]])
        def pending = DeadlineRiskReceipts.handle(dir, 'load-record', 'event')
        DeadlineRiskReceipts.handle(dir, 'save', 'event', [[pending: false]])
        def resolved = DeadlineRiskReceipts.handle(dir, 'load-record', 'event')
        then:
        pending.pending && pending.clarification == 'question'
        !resolved.containsKey('pending')
        !resolved.containsKey('clarification')
        resolved.intent.planId == 'new'
        cleanup:
        dir?.toFile()?.deleteDir()
    }

    def 'concurrent updates to events and nested intent fields do not clobber each other'() {
        given:
        def dir = Files.createTempDirectory('receipts')
        def executor = Executors.newFixedThreadPool(8)
        when:
        def tasks = (0..<40).collect { n -> executor.submit({ ->
            DeadlineRiskReceipts.handle(dir, 'merge-record', 'shared', [[intent: [("field${n}".toString()): n]]])
            DeadlineRiskReceipts.handle(dir, 'save', "event${n}".toString(), [[commandId: "c${n}".toString(), state: 'verified']])
        } as Runnable) }
        tasks*.get()
        then:
        DeadlineRiskReceipts.handle(dir, 'load-record', 'shared').intent.size() == 40
        (0..<40).every { n -> DeadlineRiskReceipts.handle(dir, 'load', "event${n}".toString()) == ["c${n}".toString()] as Set }
        cleanup:
        executor?.shutdownNow()
        dir?.toFile()?.deleteDir()
    }
}
