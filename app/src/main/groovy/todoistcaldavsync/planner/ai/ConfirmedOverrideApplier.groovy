package todoistcaldavsync.planner.ai

import todoistcaldavsync.planner.domain.Plan
import todoistcaldavsync.planner.domain.Task
import todoistcaldavsync.planner.domain.CalendarEvent

import java.time.Duration
import java.time.Instant

/** Pure deterministic transformation of task copies after exact confirmation. */
final class ConfirmedOverrideApplier {
    List<Task> apply(List<Task> tasks, AiSuggestionDecisionStore store, String decisionId,
                     AiSuggestionBundle bundle, Plan boundPlan, Instant rangeStart,
                     Instant rangeEnd, Instant now) {
        apply(tasks,store,decisionId,bundle,boundPlan,Collections.emptyList(),rangeStart,rangeEnd,now)
    }
    List<Task> apply(List<Task> tasks, AiSuggestionDecisionStore store, String decisionId,
                     AiSuggestionBundle bundle, Plan boundPlan, Collection<CalendarEvent> events,
                     Instant rangeStart, Instant rangeEnd, Instant now) {
        if (store == null || bundle == null || boundPlan == null) throw new IllegalArgumentException('store, bundle, and bound plan are required')
        if (now == null) throw new IllegalArgumentException('authoritative now is required')
        if (rangeStart == null || rangeEnd == null || !rangeEnd.isAfter(rangeStart)) throw new IllegalArgumentException('positive planning range required')
        List<Task> source=(tasks?:[]).findAll{it!=null}
        if(PlanningInputHash.taskSetHash(source)!=PlanningInputHash.taskSetHash(boundPlan.tasks))
            throw new IllegalArgumentException('caller tasks must exactly match the bound planning input')
        TemporaryPlanningOverride override=store.verifiedOverride(decisionId,bundle,boundPlan,events,now)
        if (!override.expiresAt.isAfter(now) || rangeStart.isBefore(override.rangeStart) ||
            rangeEnd.isAfter(override.rangeEnd) || rangeEnd.isAfter(override.expiresAt))
            throw new IllegalArgumentException('planning range must be fully contained by the live confirmed override')
        Set<String> sourceIds=source.collect{it.id} as Set
        if (!sourceIds.containsAll(override.taskIds)) throw new IllegalArgumentException('confirmed override references missing task')
        source.collect { Task task ->
            if (!override.taskIds.contains(task.id)) return task
            switch (override.overrideType) {
                case 'duration_minutes':
                    int minutes=(override.value as Number).intValue()
                    if(minutes<5 || minutes>480)throw new IllegalArgumentException('duration override out of bounds')
                    return copy(task, task.labels, Duration.ofMinutes(minutes),
                        "ai-confirmed-temporary:${override.suggestionId}")
                case 'context_label':
                    String label=override.value.toString()
                    if(!(label==~ /^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$/))throw new IllegalArgumentException('context label invalid')
                    List<String> labels=new ArrayList<>(task.labels)
                    if(!labels.any{it.equalsIgnoreCase(label)})labels << label
                    return copy(task,labels,task.effectiveDuration,task.durationSource)
                default:
                    throw new IllegalArgumentException("unsupported confirmed override type: ${override.overrideType}")
            }
        }
    }

    private static Task copy(Task t,List<String> labels,Duration effective,String source) {
        Task.builder().id(t.id).content(t.content).projectId(t.projectId).projectName(t.projectName)
            .labels(labels).priority(t.priority).deadline(t.deadline).dueTime(t.dueTime)
            .nativeDuration(t.nativeDuration).effectiveDuration(effective).durationSource(source)
            .manual(t.manual).allDayDue(t.allDayDue).build()
    }
}
