package todoistcaldavsync.planner.domain

/**
 * Per-side / per-item application status for auditable receipts.
 */
enum ApplyItemStatus {
    PENDING('pending'),
    APPLIED('applied'),
    SKIPPED_IDEMPOTENT('skipped_idempotent'),
    SKIPPED_PREVIEW('skipped_preview'),
    SKIPPED_UNAPPROVED('skipped_unapproved'),
    SKIPPED_PROTECTED('skipped_protected'),
    SKIPPED_MANUAL_OVERRIDE('skipped_manual_override'),
    SKIPPED_DRIFT('skipped_drift'),
    SKIPPED_NO_CHANGES('skipped_no_changes'),
    PARTIAL('partial'),
    UNKNOWN('unknown'),
    FAILED('failed'),
    ERROR_EXTERNAL_UID('error_external_uid')

    final String wire

    ApplyItemStatus(String wire) {
        this.wire = wire
    }

    static ApplyItemStatus fromWire(String value) {
        if (value == null) {
            throw new IllegalArgumentException('ApplyItemStatus must not be null')
        }
        def match = values().find { it.wire == value || it.name() == value }
        if (!match) {
            throw new IllegalArgumentException("Unknown ApplyItemStatus: ${value}")
        }
        return match
    }

    boolean isTerminalSuccess() {
        this == APPLIED || this == SKIPPED_IDEMPOTENT
    }

    boolean isBlocking() {
        this == UNKNOWN || this == FAILED || this == PARTIAL || this == ERROR_EXTERNAL_UID
    }
}
