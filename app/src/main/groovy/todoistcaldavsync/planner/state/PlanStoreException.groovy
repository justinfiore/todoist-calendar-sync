package todoistcaldavsync.planner.state

/**
 * Structured failure loading or saving a plan snapshot.
 * Not-found is represented by {@code null} from {@link PlanStore#load}, not this exception.
 */
class PlanStoreException extends RuntimeException {
    final String path
    final String context

    PlanStoreException(String message, String path, String context) {
        super(message)
        this.path = path
        this.context = context
    }

    PlanStoreException(String message, String path, String context, Throwable cause) {
        super(message, cause)
        this.path = path
        this.context = context
    }
}
