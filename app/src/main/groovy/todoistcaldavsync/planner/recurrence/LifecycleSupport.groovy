package todoistcaldavsync.planner.recurrence

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

final class LifecycleSupport {
    private LifecycleSupport() {}

    static String fingerprint(TodoistDue due) {
        if (due == null || !due.recurring || !due.completeRecurrenceTuple()) {
            throw new IllegalArgumentException('complete recurring Due tuple is required')
        }
        MessageDigest digest = MessageDigest.getInstance('SHA-256')
        String hex = digest.digest(due.recurrenceFingerprintInput().getBytes(StandardCharsets.UTF_8))
            .collect { String.format('%02x', it & 0xff) }.join()
        "sha256:${hex}"
    }

    static String taskLink(String taskId) {
        if (!(taskId ==~ /[A-Za-z0-9_-]+/)) throw new IllegalArgumentException('unsafe Todoist task id')
        "https://app.todoist.com/app/task/${taskId}"
    }
}
