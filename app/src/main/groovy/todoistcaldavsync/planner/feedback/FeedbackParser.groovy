package todoistcaldavsync.planner.feedback

import todoistcaldavsync.planner.domain.DecisionRecord
import todoistcaldavsync.planner.domain.Plan
import todoistcaldavsync.planner.domain.PlanHash
import todoistcaldavsync.planner.domain.Proposal
import todoistcaldavsync.planner.state.DecisionStore
import todoistcaldavsync.planner.util.BoundedText

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.Locale
import java.util.UUID
import java.util.function.Predicate
import java.util.function.Supplier
import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * Structured feedback parser only (no NL/LLM). Commands:
 *   approve &lt;proposal-id&gt; &lt;hash-prefix|full&gt;
 *   reject &lt;proposal-id&gt; &lt;hash-prefix|full&gt; [reason...]
 *   apply-safe &lt;proposal-id&gt; &lt;hash-prefix|full&gt;
 *   status &lt;proposal-id&gt;
 *   help
 *   request-changes &lt;proposal-id&gt; [hash] [reason...]
 *
 * <p><b>Authorization is fail-closed.</b> The default / no-arg constructor and a null
 * predicate deny all actors. Production integrations must inject an explicit allowlist
 * or policy predicate. Unknown, null, and blank actors are always denied. All commands
 * (including help/status) require authorization — there is no bypass.
 *
 * Ambiguity: any reserved command verb as a standalone token after the initial
 * verb (including in a reject reason) is REJECTED_AMBIGUOUS. Reasons that need
 * a reserved verb must quote/escape it; unquoted standalone verbs reject
 * deterministically (structured safety wins). Substrings like approved,
 * helpful, statuspage are not verbs.
 *
 * Parsing and decision storage never invoke PlanApplier.
 */
class FeedbackParser {
    static final Set<String> ACTIONS = ['APPROVE', 'REJECT', 'APPLY_SAFE', 'REQUEST_CHANGES', 'STATUS', 'HELP'] as Set

    /** Canonical command verbs (hyphen form). Underscore aliases normalize to these. */
    static final Set<String> COMMAND_VERBS = [
        'approve', 'reject', 'apply-safe', 'request-changes', 'status', 'help'
    ] as Set

    /** Deny-all policy used by default and when null is injected. */
    static final Predicate<String> DENY_ALL = { String actor -> false } as Predicate

    private static final Pattern CMD = Pattern.compile(
        /^\s*(approve|reject|apply-safe|apply_safe|request-changes|request_changes|status|help)\b(.*)$/,
        Pattern.CASE_INSENSITIVE | Pattern.DOTALL)

    /** Strip leading/trailing punctuation so verbs cannot hide in ;,.,(), etc. */
    private static final Pattern EDGE_PUNCT = Pattern.compile(
        /^[^\p{IsAlphabetic}\p{IsDigit}]+|[^\p{IsAlphabetic}\p{IsDigit}]+$/)

    private final DecisionStore decisionStore
    private final Predicate<String> authorizationPolicy
    private final Supplier<Instant> clock
    /** Per-process/parser entropy (non-secret); avoids cross-JVM candidate id collisions. */
    private final String parserEntropy
    private final java.util.concurrent.atomic.AtomicLong decisionSeq =
        new java.util.concurrent.atomic.AtomicLong()

    /**
     * Fail-closed: default authorization denies every actor. Pass an explicit
     * allowlist/predicate for production.
     */
    FeedbackParser(DecisionStore decisionStore) {
        this(decisionStore, DENY_ALL, { Instant.now() })
    }

    /**
     * @param authorizationPolicy explicit actor policy; {@code null} is treated as deny-all
     *                            (fail-closed). Never defaults to allow-all.
     */
    FeedbackParser(DecisionStore decisionStore,
                   Predicate<String> authorizationPolicy,
                   Supplier<Instant> clock = { Instant.now() }) {
        if (decisionStore == null) {
            throw new IllegalArgumentException('decisionStore is required')
        }
        this.decisionStore = decisionStore
        // Fail-closed: null or missing policy denies all. Never coerce to allow-all.
        this.authorizationPolicy = authorizationPolicy != null ? authorizationPolicy : DENY_ALL
        this.clock = clock ?: ({ Instant.now() } as Supplier)
        this.parserEntropy = UUID.randomUUID().toString()
    }

    /**
     * Convenience: allow only actors in the given set (exact match after trim).
     * Empty/null allowlist denies all.
     */
    static Predicate<String> allowlist(Collection<String> actors) {
        if (actors == null || actors.isEmpty()) {
            return DENY_ALL
        }
        Set<String> allowed = actors.findAll { it != null && it.toString().trim() }
            .collect { it.toString().trim() } as Set
        if (allowed.isEmpty()) {
            return DENY_ALL
        }
        return { String actor ->
            actor != null && !actor.trim().isEmpty() && allowed.contains(actor.trim())
        } as Predicate
    }

    /**
     * Parse and optionally validate against a loaded plan. Persists decision record.
     * Does NOT call PlanApplier.
     */
    FeedbackResult parseAndRecord(String rawText, FeedbackContext ctx) {
        if (ctx == null) {
            throw new IllegalArgumentException('context is required')
        }
        Instant now = clock.get()
        // Unknown/null/blank actor → synthetic id still fails authorization
        String actor = (ctx.actorId != null && !ctx.actorId.trim().isEmpty())
            ? ctx.actorId.trim() : 'unknown'
        String boundedRaw = BoundedText.sanitizeCommand(rawText)
        String correlation = ctx.correlationId
        if (correlation == null || correlation.trim().isEmpty()) {
            correlation = deriveCorrelationId(actor, boundedRaw, ctx)
        } else {
            correlation = correlation.trim()
        }

        if (boundedRaw == null || boundedRaw.trim().isEmpty()) {
            return reject(null, null, null, 'REJECTED_MALFORMED', actor, correlation, ctx, now,
                'empty feedback', null, null, null)
        }

        // Authorization first for all commands (including help/status) — fail-closed
        if (!isAuthorized(actor)) {
            return reject(null, null, null, 'REJECTED_UNAUTHORIZED', actor, correlation, ctx, now,
                "actor not authorized: ${actor}", null, null, null)
        }

        // Reject multi-command ambiguity (more than one known verb as separate lines/clauses)
        String trimmed = boundedRaw.trim()
        Matcher m = CMD.matcher(trimmed)
        if (!m.matches()) {
            return reject(null, null, null, 'REJECTED_MALFORMED', actor, correlation, ctx, now,
                "unrecognized command (structured only): ${truncate(trimmed, 80)}", null, null, null)
        }

        String verb = m.group(1).toLowerCase(Locale.ROOT).replace('_', '-')
        String rest = (m.group(2) ?: '').trim()
        String verbAction = switchAction(verb)

        // Ambiguous: second command verb present
        if (containsSecondCommand(rest)) {
            return reject(null, null, null, 'REJECTED_AMBIGUOUS', actor, correlation, ctx, now,
                'multiple commands in one message', null, null, verbAction)
        }

        if (verb == 'help') {
            // Non-plan action: optional plan identity only (no forged version=1/hash=none)
            DecisionRecord d = acceptedDecision('HELP', null,
                ctx.plan?.id, optionalPlanVersion(ctx.plan), optionalPlanHash(ctx.plan),
                actor, correlation, ctx, now, 'help', null)
            return finishClassified(d, null, helpText())
        }

        if (verb == 'status') {
            String proposalId = firstToken(rest)
            if (!proposalId) {
                return reject(null, null, null, 'REJECTED_MALFORMED', actor, correlation, ctx, now,
                    'status requires proposal-id', null, null, 'STATUS')
            }
            List<DecisionRecord> prior = decisionStore.listForProposal(proposalId)
            DecisionRecord d = acceptedDecision('STATUS', proposalId,
                ctx.plan?.id, optionalPlanVersion(ctx.plan), optionalPlanHash(ctx.plan),
                actor, correlation, ctx, now, "status count=${prior.size()}", null)
            return finishClassified(d, null, "decisions=${prior.size()}")
        }

        // approve / reject / apply-safe / request-changes need proposal + hash
        List<String> tokens = tokenize(rest)
        if (tokens.size() < 2 && verb != 'request-changes') {
            return reject(null, null, null, 'REJECTED_MALFORMED', actor, correlation, ctx, now,
                "${verb} requires <proposal-id> <hash-prefix>", null, null, verbAction)
        }
        if (tokens.isEmpty()) {
            return reject(null, null, null, 'REJECTED_MALFORMED', actor, correlation, ctx, now,
                "${verb} requires <proposal-id>", null, null, verbAction)
        }

        String proposalId = tokens[0]
        String hashToken = tokens.size() >= 2 ? tokens[1] : null
        String reason = tokens.size() > 2 ? tokens.subList(2, tokens.size()).join(' ') : ctx.reason
        reason = BoundedText.sanitizeReason(reason)

        String action = verbAction

        // Validate against plan when provided (syntax/auth/plan/hash before atomic correlation)
        Plan plan = ctx.plan
        if (plan == null) {
            return reject(proposalId, null, null, 'REJECTED_STALE', actor, correlation, ctx, now,
                'no plan loaded for validation', null, null, action)
        }

        String planHash = PlanHash.compute(plan)
        Proposal expected = Proposal.fromPlan(plan)

        if (proposalId != expected.id && proposalId != plan.id) {
            // Allow short form prop id mismatch as wrong identity
            return reject(proposalId, plan.id, plan.version, 'REJECTED_WRONG_IDENTITY', actor, correlation, ctx, now,
                "proposal id mismatch: got ${proposalId}, expected ${expected.id}", planHash, null, action)
        }

        if (!hashToken) {
            return reject(proposalId, plan.id, plan.version, 'REJECTED_MALFORMED', actor, correlation, ctx, now,
                'hash prefix/full required', planHash, null, action)
        }

        if (!hashMatches(planHash, hashToken)) {
            return reject(proposalId, plan.id, plan.version, 'REJECTED_STALE', actor, correlation, ctx, now,
                "hash mismatch (stale or wrong plan)", planHash, null, action)
        }

        // Optional explicit version check via metadata
        if (ctx.expectedPlanVersion != null && ctx.expectedPlanVersion != plan.version) {
            return reject(proposalId, plan.id, plan.version, 'REJECTED_STALE', actor, correlation, ctx, now,
                "plan version mismatch: expected ${ctx.expectedPlanVersion}, got ${plan.version}",
                planHash, null, action)
        }

        // Plan-bound candidate; correlation classification is atomic under DecisionStore lock
        DecisionRecord candidate = acceptedDecision(action, expected.id, plan.id, plan.version, planHash,
            actor, correlation, ctx, now, reason, null)
        return finishClassified(candidate, candidate.toApproval(), 'accepted')
    }

    /**
     * Persist candidate via atomic correlation classification. Never uses a stale
     * listForCorrelation snapshot outside the store lock for authorization.
     */
    private FeedbackResult finishClassified(DecisionRecord candidate,
                                            todoistcaldavsync.planner.domain.Approval approvalHint,
                                            String okMessage) {
        DecisionStore.DecisionAppendOutcome outcome = decisionStore.appendClassified(candidate)
        if (outcome.isIdempotentReplay()) {
            // Replay never authorizes: accepted=false, approval=null
            return FeedbackResult.replayed(outcome.persisted, 'idempotent replay')
        }
        if (outcome.isConflict()) {
            return FeedbackResult.rejected(outcome.persisted,
                outcome.persisted?.reason ?: 'correlation conflict')
        }
        if (outcome.accepted || outcome.isNewAccepted()) {
            def approval = outcome.persisted?.toApproval() ?: approvalHint
            // Only surface Approval for exact ACCEPTED APPROVE
            if (outcome.persisted != null && outcome.persisted.action == 'APPROVE' &&
                outcome.persisted.isAccepted()) {
                approval = outcome.persisted.toApproval()
            } else if (outcome.persisted?.action != 'APPROVE') {
                approval = null
            }
            return FeedbackResult.ok(outcome.persisted, approval, okMessage ?: 'accepted')
        }
        // Non-accepted new record (should not happen for accepted candidates)
        return FeedbackResult.rejected(outcome.persisted,
            outcome.persisted?.reason ?: 'not accepted')
    }

    private boolean isAuthorized(String actor) {
        if (actor == null || actor.trim().isEmpty() || actor == 'unknown') {
            return false
        }
        try {
            return authorizationPolicy.test(actor)
        } catch (Exception ignored) {
            return false
        }
    }

    private FeedbackResult reject(String proposalId, String planId, Integer planVersion,
                                  String status, String actor, String correlation,
                                  FeedbackContext ctx, Instant now, String reason,
                                  String planHash, String previousId,
                                  String explicitAction = null) {
        DecisionRecord.Builder b = DecisionRecord.builder()
            .id(decisionId(status, proposalId ?: 'none', actor, correlation, now, 'rej'))
            .proposalId(proposalId)
            .planId(planId)
            .action(inferActionFromStatus(status, explicitAction))
            .status(status)
            .actorId(actor)
            .correlationId(correlation)
            .destination(ctx?.destination)
            .threadId(ctx?.threadId)
            .messageId(ctx?.messageId)
            .decidedAt(now)
            .reason(reason)
            .previousDecisionId(previousId)
            .conflictStatus(status == 'REJECTED_REPLAY_CONFLICT' ? 'conflict' : 'none')
        if (planVersion != null && planVersion >= 1) {
            b.planVersion(planVersion)
        }
        if (planHash) {
            b.planHash(planHash)
        }
        DecisionRecord d = b.build()
        // Atomic under store lock so concurrent accept cannot race a stale reject path
        DecisionStore.DecisionAppendOutcome outcome = decisionStore.appendClassified(d)
        return FeedbackResult.rejected(outcome.persisted ?: d, reason)
    }

    /**
     * Explicit status→action mapping only. Never scans free-text reasons for substrings
     * like {@code helpful}/{@code statuspage} (those stay REJECT audit labels).
     * Optional {@code explicitAction} wins when it is a known action.
     */
    static String inferActionFromStatus(String status, String explicitAction) {
        if (explicitAction != null) {
            String a = explicitAction.trim().toUpperCase(Locale.ROOT)
            if (ACTIONS.contains(a)) {
                return a
            }
        }
        if (status == null) {
            return 'REJECT'
        }
        switch (status) {
            case 'REJECTED_MALFORMED':
            case 'REJECTED_AMBIGUOUS':
            case 'REJECTED_STALE':
            case 'REJECTED_UNAUTHORIZED':
            case 'REJECTED_REPLAY_CONFLICT':
            case 'REJECTED_WRONG_IDENTITY':
                return 'REJECT'
            case 'IDEMPOTENT_REPLAY':
                return 'REJECT'
            case 'ACCEPTED':
                return 'REJECT'
            default:
                return 'REJECT'
        }
    }

    /**
     * Build accepted decision. Plan-bound actions (APPROVE/REJECT/APPLY_SAFE/REQUEST_CHANGES)
     * require exact plan id/version/hash via {@link DecisionRecord} validation.
     * Non-plan HELP/STATUS may omit plan identity (null version/hash) — never forge 1/'none'.
     */
    private DecisionRecord acceptedDecision(String action, String proposalId, String planId,
                                            Integer planVersion, String planHash,
                                            String actor, String correlation,
                                            FeedbackContext ctx, Instant now,
                                            String reason, String previousId) {
        DecisionRecord.Builder b = DecisionRecord.builder()
            .id(decisionId(action, proposalId ?: 'none', actor, correlation, now, 'ok'))
            .proposalId(proposalId)
            .planId(planId)
            .action(action)
            .status('ACCEPTED')
            .actorId(actor)
            .correlationId(correlation)
            .destination(ctx?.destination)
            .threadId(ctx?.threadId)
            .messageId(ctx?.messageId)
            .decidedAt(now)
            .reason(reason)
            .previousDecisionId(previousId)
            .conflictStatus('none')
        if (planVersion != null) {
            b.planVersion(planVersion)
        }
        if (planHash != null) {
            b.planHash(planHash)
        }
        return b.build()
    }

    private static Integer optionalPlanVersion(Plan plan) {
        plan != null ? plan.version : null
    }

    private static String optionalPlanHash(Plan plan) {
        plan != null ? PlanHash.compute(plan) : null
    }

    private static String switchAction(String verb) {
        switch (verb) {
            case 'approve': return 'APPROVE'
            case 'reject': return 'REJECT'
            case 'apply-safe': return 'APPLY_SAFE'
            case 'request-changes': return 'REQUEST_CHANGES'
            case 'status': return 'STATUS'
            case 'help': return 'HELP'
            default: return 'REJECT'
        }
    }

    static boolean hashMatches(String fullHash, String token) {
        if (!fullHash || !token) {
            return false
        }
        String t = token.trim().toLowerCase(Locale.ROOT)
        String h = fullHash.toLowerCase(Locale.ROOT)
        if (t == h) {
            return true
        }
        // prefix match, minimum 8 hex chars to reduce ambiguity
        if (t.length() < 8) {
            return false
        }
        return h.startsWith(t)
    }

    /**
     * True when any remaining token is a reserved command verb (token-boundary,
     * case-insensitive). Detects a single trailing verb and multi-command lines;
     * does not match substrings (approved, helpful, statuspage).
     */
    private static boolean containsSecondCommand(String rest) {
        if (!rest) {
            return false
        }
        for (String raw : tokenize(rest)) {
            if (isCommandVerbToken(raw)) {
                return true
            }
        }
        return false
    }

    /**
     * Token is a command verb after edge-punctuation strip and underscore→hyphen.
     * Whole-token only; substrings are not verbs.
     */
    static boolean isCommandVerbToken(String raw) {
        if (!raw) {
            return false
        }
        String t = EDGE_PUNCT.matcher(raw.trim()).replaceAll('')
        if (!t) {
            return false
        }
        String norm = t.toLowerCase(Locale.ROOT).replace('_', '-')
        return COMMAND_VERBS.contains(norm)
    }

    private static List<String> tokenize(String s) {
        if (!s) {
            return []
        }
        // Whitespace and newlines are token boundaries
        return s.trim().split(/\s+/).findAll { it } as List
    }

    private static String firstToken(String s) {
        def t = tokenize(s)
        t ? t[0] : null
    }

    private String decisionId(String action, String proposalId, String actor,
                              String correlation, Instant now, String tag) {
        // Per-parser entropy + monotonic seq: not process-local-only; store still
        // allocates collision-free durable ids under lock when needed.
        long seq = decisionSeq.incrementAndGet()
        String raw = "${parserEntropy}|${action}|${proposalId}|${actor}|${correlation}|${now}|${tag}|${seq}"
        MessageDigest md = MessageDigest.getInstance('SHA-256')
        String hex = md.digest(raw.getBytes(StandardCharsets.UTF_8))
            .collect { String.format('%02x', it & 0xff) }.join()
        return "dec-${tag}-${hex.substring(0, 16)}"
    }

    private static String helpText() {
        return '''Structured commands:
  approve <proposal-id> <hash-prefix>
  reject <proposal-id> <hash-prefix> [reason]
  apply-safe <proposal-id> <hash-prefix>
  status <proposal-id>
  help

Production integrations must configure an explicit actor allowlist/predicate;
default FeedbackParser authorization denies all actors.'''
    }

    private static String truncate(String s, int max) {
        if (s == null) return ''
        s.length() <= max ? s : s.substring(0, max) + '…'
    }

    /**
     * Stable bounded non-secret correlation from actor + normalized command + destination/thread/message.
     * Prefer supplied platform message ID. Same retry derives same id; distinct inputs differ.
     * Never uses epoch millis.
     */
    static String deriveCorrelationId(String actor, String rawCommand, FeedbackContext ctx) {
        String msgId = ctx?.messageId?.trim()
        if (msgId) {
            // Prefer platform message id (bounded, hashed with actor for namespacing)
            return 'corr-' + sha256Prefix(
                "msg|${norm(actor)}|${norm(msgId)}|${norm(ctx?.destination)}|${norm(ctx?.threadId)}", 24)
        }
        String cmd = normalizeCommandForCorrelation(rawCommand)
        String material = [
            'v1',
            norm(actor),
            cmd,
            norm(ctx?.destination),
            norm(ctx?.threadId),
            norm(ctx?.plan?.id),
            ctx?.plan != null ? String.valueOf(ctx.plan.version) : '',
            ctx?.plan != null ? (PlanHash.compute(ctx.plan) ?: '') : '',
            norm(ctx?.expectedPlanVersion != null ? ctx.expectedPlanVersion.toString() : '')
        ].join('|')
        return 'corr-' + sha256Prefix(material, 24)
    }

    private static String normalizeCommandForCorrelation(String raw) {
        if (raw == null) {
            return ''
        }
        return raw.trim().toLowerCase(Locale.ROOT).replaceAll(/\s+/, ' ')
    }

    private static String norm(String s) {
        s == null ? '' : s.trim()
    }

    private static String sha256Prefix(String material, int hexChars) {
        MessageDigest md = MessageDigest.getInstance('SHA-256')
        String hex = md.digest(material.getBytes(StandardCharsets.UTF_8))
            .collect { String.format('%02x', it & 0xff) }.join()
        int n = Math.min(hexChars, hex.length())
        return hex.substring(0, n)
    }

    static final class FeedbackContext {
        final String actorId
        final String correlationId
        final String destination
        final String threadId
        final String messageId
        final String reason
        final Plan plan
        final Integer expectedPlanVersion

        FeedbackContext(Map opts = [:]) {
            this.actorId = opts.actorId?.toString()
            this.correlationId = opts.correlationId?.toString()
            this.destination = opts.destination?.toString()
            this.threadId = opts.threadId?.toString()
            this.messageId = opts.messageId?.toString()
            this.reason = opts.reason != null ? BoundedText.sanitizeReason(opts.reason.toString()) : null
            this.plan = opts.plan instanceof Plan ? (Plan) opts.plan : null
            this.expectedPlanVersion = opts.expectedPlanVersion != null ?
                opts.expectedPlanVersion as Integer : null
        }
    }

    static final class FeedbackResult {
        final DecisionRecord decision
        final todoistcaldavsync.planner.domain.Approval approval
        final String message
        final boolean accepted
        /** True when this result is an IDEMPOTENT_REPLAY (never apply-safe). */
        final boolean replayed

        private FeedbackResult(DecisionRecord decision,
                               todoistcaldavsync.planner.domain.Approval approval,
                               String message, boolean accepted, boolean replayed = false) {
            this.decision = decision
            this.approval = approval
            this.message = message
            this.accepted = accepted
            this.replayed = replayed
        }

        static FeedbackResult ok(DecisionRecord d,
                                 todoistcaldavsync.planner.domain.Approval a,
                                 String msg) {
            new FeedbackResult(d, a, msg, true, false)
        }

        /**
         * Idempotent replay: never {@code accepted=true}, never carries Approval.
         * Host anti-pattern {@code if (accepted || approval)} cannot apply.
         */
        static FeedbackResult replayed(DecisionRecord d, String msg) {
            new FeedbackResult(d, null, msg, false, true)
        }

        static FeedbackResult rejected(DecisionRecord d, String msg) {
            new FeedbackResult(d, null, msg, false, false)
        }
    }
}
