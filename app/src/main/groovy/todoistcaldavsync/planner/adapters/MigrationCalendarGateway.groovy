package todoistcaldavsync.planner.adapters

import todoistcaldavsync.planner.migration.LegacyMigrationPermit

/** Full provider payloads, separate from normal planner ownership/write capabilities. */
interface MigrationCalendarGateway {
    /** Complete configured managed collections, not a lossy range/domain-event export. */
    List<Map> inventoryLegacyResources()
    Map readLegacyResource(String destination, String id)
    void deleteLegacyResource(String destination, String id, LegacyMigrationPermit permit)
    /** Restore a private copy; provider ID/version can change and must be recorded. */
    Map restoreLegacyResource(String destination, String id, LegacyMigrationPermit permit)
}
