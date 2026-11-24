package todoistcaldavsync.planner.sync

import todoistcaldavsync.planner.adapters.ProjectionCalendarGateway
import todoistcaldavsync.planner.config.PlannerConfig
import todoistcaldavsync.planner.domain.CalendarEvent
import todoistcaldavsync.planner.domain.ManagedEventIds
import todoistcaldavsync.planner.domain.ProjectionMetadata
import todoistcaldavsync.planner.domain.Task

import java.time.Duration
import java.time.Instant

/** Project existing schedules verbatim, including outside-hours and date-only Dues. No scheduler. */
final class RoutedProjections {
    private final PlannerConfig config
    private final ProjectionCalendarGateway calendar

    RoutedProjections(PlannerConfig config, ProjectionCalendarGateway calendar) {
        if (config.routing == null) throw new IllegalArgumentException('explicit routed configuration required')
        this.config = config
        this.calendar = calendar
    }

    CalendarEvent fromTask(Task task, String humanDescription = null) {
        String destination = config.projectionDestination(task)
        if (destination == null || task.todoistDue == null) return null
        fromMembers([member(task)], destination, task.content,
            humanDescription ?: 'https://app.todoist.com/app/task/' + task.id, false, 1, task.allDayDue)
    }

    Map member(Task task) {
        if (task.todoistDue == null || task.dueTime == null ||
            task.effectiveDuration != Duration.ofMinutes(task.effectiveDuration.toMinutes()) ||
            task.effectiveDuration.toMinutes() < 1 ||
            (task.nativeDuration != null && task.nativeDuration != Duration.ofMinutes(task.nativeDuration.toMinutes()))) {
            throw new IllegalArgumentException('Projection requires Due and positive whole-minute effective/native duration')
        }
        Instant end = task.allDayDue ? task.todoistDue.localDate(config.timezone).plusDays(1)
            .atStartOfDay(config.timezone).toInstant() : task.dueTime.plus(task.effectiveDuration)
        [taskId: task.id, occurrenceKey: task.occurrenceIdentity().occurrenceKey(), start: task.dueTime.toString(),
            end: end.toString(), due: task.todoistDue.snapshotMap(), nativeMinutes: task.nativeDuration?.toMinutes(),
            effectiveMinutes: task.effectiveDuration.toMinutes(), taskUpdated: task.updatedAt?.toString()]
    }

    CalendarEvent fromMembers(List<Map> members, String destination, String title, String humanDescription,
                              boolean historical = false, long revision = 1, boolean allDay = false) {
        String uid = historical ? ManagedEventIds.uidForBlock('history:' + members[0].occurrenceKey) :
            ManagedEventIds.uidForOccurrences(members*.occurrenceKey)
        def metadata = new ProjectionMetadata([schemaVersion: 1, uid: uid, destination: destination,
            revision: revision, routingFingerprint: config.routing.fingerprint, historical: historical,
            allDay: allDay, members: members])
        CalendarEvent.builder().id(calendar.projectionResourceId(destination, uid)).uid(uid)
            .calendarName(destination).title(title).start(Instant.parse(members[0].start.toString()))
            .end(Instant.parse(members[-1].end.toString())).allDay(allDay)
            .description(metadata.description(humanDescription, uid, 'sync')).build()
    }
}
