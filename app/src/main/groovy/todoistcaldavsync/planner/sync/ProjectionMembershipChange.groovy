package todoistcaldavsync.planner.sync

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import todoistcaldavsync.planner.adapters.ProjectionCalendarGateway
import todoistcaldavsync.planner.adapters.TodoistScheduleGateway
import todoistcaldavsync.planner.config.PlannerConfig
import todoistcaldavsync.planner.domain.CalendarEvent
import todoistcaldavsync.planner.domain.ProjectionMetadata
import todoistcaldavsync.planner.domain.Task
import todoistcaldavsync.planner.recurrence.LifecycleClassifier
import todoistcaldavsync.planner.recurrence.LifecycleState
import todoistcaldavsync.planner.recurrence.LifecycleSupport
import todoistcaldavsync.planner.recurrence.TodoistDue
import todoistcaldavsync.planner.state.ApplicationStateStore

import java.time.Instant

/** Split changed/departed members without moving residual tasks or mutating lifecycle state. */
final class ProjectionMembershipChange {
    private final ApplicationStateStore store
    private final ProjectionCalendarGateway calendar
    private final TodoistScheduleGateway todoist
    private final PlannerConfig config
    private final RoutedProjections projections

    ProjectionMembershipChange(ApplicationStateStore store, ProjectionCalendarGateway calendar,
                               TodoistScheduleGateway todoist, PlannerConfig config) {
        this.store = store
        this.calendar = calendar
        this.todoist = todoist
        this.config = config
        this.projections = new RoutedProjections(config, calendar)
    }

    /** Deletion evidence is a durably staged provider item, not absence from an inventory. */
    Map apply(CalendarEvent source, List<Task> inventory, Map<String, Map> items = [:], boolean preview = false) {
        ProjectionMetadata metadata = ProjectionMetadata.read(source)
        if (metadata.value.historical || metadata.value.routingFingerprint != config.routing.fingerprint ||
            source.start.toString() != metadata.value.members[0].start ||
            source.end.toString() != metadata.value.members[-1].end || source.allDay != metadata.value.allDay) {
            throw new IllegalStateException('membership transition requires unchanged current schedule and routing baseline')
        }
        Map taskBaselines = [:]
        Map reports = [:]
        List<CalendarEvent> targets = []
        List<Map> run = []
        boolean changed = false
        String note = ProjectionMetadata.humanDescription(source)
        Closure flush = {
            if (run) {
                targets << projections.fromMembers(new ArrayList<Map>(run), source.calendarName,
                    source.title, note, false, 1, source.allDay)
                run.clear()
            }
        }
        metadata.value.members.each { Map member ->
            String id = member.taskId.toString()
            Map raw = todoist.fetchTask(id)
            if (raw != null && raw.id?.toString() != id) {
                throw new IllegalStateException('fresh task read returned a different identity')
            }
            taskBaselines[id] = raw == null ? null : copy(raw)
            Map item = items[id]
            boolean deleted = raw != null && flag(raw.is_deleted) || raw == null && item?.id?.toString() == id && flag(item.is_deleted)
            Map completion = raw != null ? raw : item?.id?.toString() == id ? item : [:]
            boolean complete = !deleted && (flag(completion.checked) || flag(completion.is_completed) || completion.completed_at != null)
            Task live = raw == null || deleted || complete ? null : normalize(raw, inventory)
            boolean advanced = live != null && live.occurrenceIdentity().occurrenceKey() != member.occurrenceKey
            if (raw == null && !deleted && !complete) throw new IllegalStateException('partial feed absence is not proof of task departure: ' + id)
            if (advanced && !verifiedAdvancement(live, member)) {
                throw new IllegalStateException('unexplained occurrence change requires lifecycle reconciliation: ' + id)
            }
            CalendarEvent individual = live == null ? null : projections.fromTask(live, note)
            boolean same = !advanced && !deleted && !complete && individual != null &&
                individual.calendarName == source.calendarName && individual.allDay == source.allDay &&
                live.todoistDue.snapshotMap() == member.due && live.nativeDuration?.toMinutes() == member.nativeMinutes &&
                live.effectiveDuration.toMinutes() == member.effectiveMinutes
            if (same) {
                run << member
                return
            }
            if (!deleted && !complete && !advanced && (live.updatedAt == null || source.lastModified == null ||
                member.taskUpdated == null || !live.updatedAt.isAfter(source.lastModified) ||
                !live.updatedAt.isAfter(Instant.parse(member.taskUpdated.toString())))) {
                throw new IllegalStateException('member edit requires strictly newer task timestamps: ' + id)
            }
            changed = true
            flush.call()
            if (complete || advanced) {
                targets << projections.fromMembers([member], source.calendarName, source.title, note, true, 1, source.allDay)
            }
            if (individual != null) targets << individual
            reports[id] = deleted ? 'deleted' : complete ? 'completed' : advanced ?
                (individual != null ? 'advanced_projected' : live.todoistDue == null ? 'advanced_undated' : 'advanced_ineligible') :
                live.todoistDue == null ? 'due_removed' : individual == null ? 'ineligible_or_unroutable' : 'split_or_relocated'
        }
        if (!changed) return [status: 'unchanged', reports: [:]]
        flush.call()
        // A singleton edit at the same identity is handled by conditional in-place reconciliation.
        if (targets.any { it.uid == source.uid && it.calendarName == source.calendarName }) {
            throw new IllegalStateException('same-resource schedule edit requires single projection reconciliation')
        }
        if (preview) return [status: 'preview_replacement', reports: reports,
            targets: targets.collect { ProjectionMetadata.eventMap(it) }]
        targets = targets.collect { CalendarEvent target ->
            ProjectionMetadata.eventFromMap(ProjectionMetadata.eventMap(target) +
                [id: calendar.newProjectionResourceId(target.calendarName, target.uid)])
        }
        replacement().replace([source], targets, [taskBaselines: taskBaselines, reports: reports, items: items])
    }

    Map resume(String operationId) { replacement().resume(operationId) }

    private ProjectionReplacementService replacement() {
        new ProjectionReplacementService(store, calendar, { Map evidence ->
            if (evidence.routingFingerprint != config.routing.fingerprint) {
                throw new IllegalStateException('routing changed during membership replacement; review required')
            }
            evidence.taskBaselines.each { String id, Object expected ->
                Map live = todoist.fetchTask(id)
                if ((live == null ? null : copy(live)) != expected) {
                    throw new IllegalStateException('task changed during membership replacement: ' + id)
                }
            }
        })
    }

    private boolean verifiedAdvancement(Task live, Map member) {
        String prefix = member.taskId.toString() + ':'
        long previous = Long.parseLong(member.occurrenceKey.toString().substring(prefix.length()))
        if (live.completedCount <= previous || live.lifecycleMarker?.completedCount != previous ||
            live.lifecycleMarker?.taskId != live.id || member.due.recurring != true) return false
        TodoistDue before = TodoistDue.fromSnapshotMap(member.due as Map)
        live.lifecycleMarker.recurrenceFingerprint == LifecycleSupport.fingerprint(before) &&
            (new LifecycleClassifier().classify(live, config.recurrence.seenLabel).state == LifecycleState.ADVANCED ||
                live.todoistDue == null && live.labels.any { it.equalsIgnoreCase(config.recurrence.seenLabel) })
    }

    private Task normalize(Map raw, List<Task> inventory) {
        Map enriched = copy(raw)
        Task known = inventory.find { it.id == raw.id?.toString() && it.projectId == raw.project_id?.toString() }
        if (!enriched.project_name && known != null) enriched.project_name = known.projectName
        Task.fromTodoistMap(enriched, config.durationResolver, config.manualLabel, config.timezone)
    }

    private static boolean flag(Object value) { value == true || value == 1 }
    private static Map copy(Map value) { new JsonSlurper().parseText(JsonOutput.toJson(value)) as Map }
}
