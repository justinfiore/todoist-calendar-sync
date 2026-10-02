package todoistcaldavsync.planner.recurrence

import todoistcaldavsync.planner.config.PlannerConfig
import todoistcaldavsync.planner.domain.Task

import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.function.Consumer

/** First-observation, legacy onboarding, and native-advancement policy. */
final class RecurrenceLifecycleManager {
    private final PlannerConfig config
    private final LifecycleMarkerWriter writer
    private final Consumer<String> log
    private final LifecycleClassifier classifier = new LifecycleClassifier()

    RecurrenceLifecycleManager(PlannerConfig config, LifecycleMarkerWriter writer,
                               Consumer<String> log = { ignored -> }) {
        if (config == null || writer == null) throw new IllegalArgumentException('config and writer are required')
        this.config = config; this.writer = writer; this.log = log
    }

    LifecycleTransition process(Task live) {
        if (!config.recurrence.enabled || live?.todoistDue?.recurring != true) {
            return LifecycleTransition.noop('not_eligible')
        }
        LifecycleClassification classification = classifier.classify(live, config.recurrence.seenLabel)
        if (classification.state == LifecycleState.SENTINEL_MISSING &&
            live.lifecycleMarker?.deadlineMode == 'legacy' &&
            has(live, config.recurrence.onboardLabel)) {
            return onboardLegacy(live)
        }
        switch (classification.state) {
            case LifecycleState.UNMARKED:
                return initialize(live)
            case LifecycleState.ADVANCED:
                return advance(live, classification)
            case LifecycleState.INITIALIZED:
                return LifecycleTransition.noop('current')
            default:
                return LifecycleTransition.frozen(classification.reason ?: classification.state.name().toLowerCase())
        }
    }

    private LifecycleTransition initialize(Task task) {
        boolean afterCutoff = task.createdAt != null && !task.createdAt.isBefore(config.recurrence.rolloutCutoff)
        boolean requested = has(task, config.recurrence.onboardLabel)
        boolean existingDeadline = task.deadlineDate != null
        LocalDate source = task.todoistDue?.localDate(config.timezone)
        if (!afterCutoff && !requested) {
            log.accept("legacy recurrence candidate name=${task.content} id=${task.id} url=${LifecycleSupport.taskLink(task.id)}")
        }
        boolean managed = existingDeadline || afterCutoff || requested
        String deadlineSource = existingDeadline ? 'existing_deadline' :
            (afterCutoff ? 'initial_user_due' : (requested ? 'legacy_user_due' : 'legacy_pending'))
        LocalDate deadline = existingDeadline ? task.deadlineDate : (managed ? source : null)
        List<String> labels = new ArrayList<>(task.labels)
        if (managed && !has(task, config.recurrence.seenLabel)) labels << config.recurrence.seenLabel
        LifecycleMarker marker = marker(task, deadline, managed ? 'managed' : 'legacy', deadlineSource,
            managed ? null : source, 1L)
        Task verified = writer.write(task, marker, deadline?.toString(), labels)
        if (requested && managed) {
            List<String> finalLabels = verified.labels.findAll {
                !it.equalsIgnoreCase(config.recurrence.onboardLabel)
            }
            LifecycleMarker finalMarker = marker(verified, deadline, 'managed', deadlineSource,
                null, marker.markerGeneration + 1L)
            verified = writer.write(verified, finalMarker, deadline?.toString(), finalLabels)
        }
        new LifecycleTransition('initialized', verified, 0, false)
    }

    private LifecycleTransition onboardLegacy(Task task) {
        LifecycleMarker previous = task.lifecycleMarker
        LocalDate deadline = previous.pendingLegacySourceDate
        if (deadline == null) return LifecycleTransition.frozen('legacy_source_missing')
        List<String> stagedLabels = new ArrayList<>(task.labels)
        if (!has(task, config.recurrence.seenLabel)) stagedLabels << config.recurrence.seenLabel
        LifecycleMarker staged = marker(task, deadline, 'managed', 'legacy_user_due',
            null, previous.markerGeneration + 1L)
        Task verified = writer.write(task, staged, deadline.toString(), stagedLabels)

        List<String> finalLabels = verified.labels.findAll {
            !it.equalsIgnoreCase(config.recurrence.onboardLabel)
        }
        LifecycleMarker committed = marker(verified, deadline, 'managed', 'legacy_user_due',
            null, staged.markerGeneration + 1L)
        verified = writer.write(verified, committed, deadline.toString(), finalLabels)
        new LifecycleTransition('onboarded', verified, 0, false)
    }

    private LifecycleTransition advance(Task task, LifecycleClassification classification) {
        LifecycleMarker previous = task.lifecycleMarker
        boolean managed = previous.deadlineMode == 'managed'
        if (classification.skippedOccurrences > 0) {
            log.accept("recurrence count jump id=${task.id} skipped=${classification.skippedOccurrences} " +
                "active=${task.completedCount} url=${LifecycleSupport.taskLink(task.id)}")
        }
        LocalDate deadline = managed ? task.todoistDue.localDate(config.timezone) : null
        LifecycleMarker next = marker(task, deadline, previous.deadlineMode, previous.deadlineSource,
            previous.pendingLegacySourceDate, previous.markerGeneration + 1L)
        Task verified = writer.write(task, next, deadline?.toString(), null)
        new LifecycleTransition('advanced', verified, classification.skippedOccurrences, false)
    }

    private LifecycleMarker marker(Task task, LocalDate deadline, String mode, String source,
                                   LocalDate pending, long generation) {
        new LifecycleMarker(taskId: task.id, completedCount: task.completedCount,
            deadlineMode: mode, deadlineSource: source, deadlineDate: deadline,
            pendingLegacySourceDate: pending, lastVerifiedDue: task.todoistDue.date,
            recurrenceFingerprint: LifecycleSupport.fingerprint(task.todoistDue),
            lastPlannerDue: task.lifecycleMarker?.lastPlannerDue,
            markerGeneration: generation, lastCommandId: UUID.randomUUID().toString())
    }

    private static boolean has(Task task, String label) {
        task.labels.any { it.equalsIgnoreCase(label) }
    }
}

final class LifecycleTransition {
    final String action
    final Task task
    final long skippedOccurrences
    final boolean frozen
    LifecycleTransition(String action, Task task, long skippedOccurrences, boolean frozen) {
        this.action = action; this.task = task; this.skippedOccurrences = skippedOccurrences; this.frozen = frozen
    }
    static LifecycleTransition noop(String reason) { new LifecycleTransition(reason, null, 0, false) }
    static LifecycleTransition frozen(String reason) { new LifecycleTransition(reason, null, 0, true) }
}
