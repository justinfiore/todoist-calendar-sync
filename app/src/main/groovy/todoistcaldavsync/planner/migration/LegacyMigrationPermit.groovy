package todoistcaldavsync.planner.migration

import java.time.Instant
import java.util.function.Supplier

/** Only a live, approved exact manifest can grant access to unowned legacy resources. */
final class LegacyMigrationPermit {
    private final MigrationState state
    final String manifestHash
    final String action
    private final Supplier<Instant> clock

    LegacyMigrationPermit(MigrationState state, String manifestHash, String action, Supplier<Instant> clock) {
        if (state == null || !manifestHash || !(action in ['delete', 'restore']) || clock == null) {
            throw new IllegalArgumentException('exact migration action/evidence required')
        }
        this.state = state; this.manifestHash = manifestHash; this.action = action; this.clock = clock
    }

    Map resource(String destination, String id) {
        Map live = state.read()
        Map approval = action == 'delete' ? live?.approval : live?.rollbackApproval
        if (live?.manifestHash != manifestHash || !approval || approval.manifestHash != manifestHash ||
            !Instant.parse(approval.expiresAt.toString()).isAfter(clock.get()) ||
            !(live.stage in (action == 'delete' ? ['DELETION_APPROVED'] : ['ROLLING_BACK']))) {
            throw new IllegalStateException('current manifest-bound migration approval required')
        }
        Map row = live.manifest.candidates.find { it.resource.destination == destination && it.resource.id == id }?.resource
        if (row == null || !row.etag || !row.uid || !(row.payload instanceof Map || row.payload instanceof String)) {
            throw new IllegalStateException('resource is not an approved legacy deletion/restore candidate')
        }
        row
    }

    void requireUnchanged(Map expected, Map live) {
        if (expected == null || live == null || expected != live) {
            throw new IllegalStateException('reviewed legacy resource changed; new review required')
        }
    }
}
