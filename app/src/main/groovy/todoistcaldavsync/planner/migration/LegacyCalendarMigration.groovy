package todoistcaldavsync.planner.migration

import todoistcaldavsync.planner.ProductionIntegrationConfig
import todoistcaldavsync.planner.adapters.*
import todoistcaldavsync.planner.config.PlannerConfig
import todoistcaldavsync.planner.domain.*
import todoistcaldavsync.planner.state.ApplicationStateStore
import todoistcaldavsync.planner.sync.RoutedProjections
import todoistcaldavsync.planner.sync.ProjectionReplacementService
import todoistcaldavsync.planner.writeback.CalendarWritebackDecision

import java.nio.file.*
import java.time.Duration
import java.time.Instant
import java.util.function.Supplier

/** Reviewed deletion-first handoff. This service has no Todoist mutation port or scheduler. */
final class LegacyCalendarMigration {
    private final PlannerConfig config
    private final ProductionIntegrationConfig integration
    private final ApplicationStateStore store
    private final ProjectionCalendarGateway calendar
    private final MigrationCalendarGateway legacy
    private final TodoistMigrationGateway todoist
    private final Supplier<Instant> clock
    final MigrationState state

    LegacyCalendarMigration(PlannerConfig config, ProductionIntegrationConfig integration,
                            ApplicationStateStore store, ProjectionCalendarGateway calendar,
                            TodoistMigrationGateway todoist, Supplier<Instant> clock = { Instant.now() }) {
        if (config.routing == null || !(calendar instanceof MigrationCalendarGateway)) {
            throw new IllegalArgumentException('migration requires routed destinations and full-payload calendar capability')
        }
        this.config = config; this.integration = integration; this.store = store
        this.calendar = calendar; this.legacy = calendar as MigrationCalendarGateway
        this.todoist = todoist; this.clock = clock; this.state = new MigrationState(store.directory)
    }

    /** Inventory has no remote writes, no credential export, and never resets an in-flight migration. */
    Map inventory() {
        store.withMutationLock {
            Map previous = state.read()
            if (previous != null && !(previous.stage in ['INVENTORIED', 'DELETION_APPROVED'] && !previous.deletions && !previous.created)) {
                throw new IllegalStateException('in-flight migration inventory cannot be replaced; retain/review its evidence')
            }
            requireNoPendingOperations()
            Map account = todoist.fetchAccountIdentity()
            List<Map> tasks = todoist.fetchTasks()
            Map byUid = tasks.collectEntries { [(CalendarWritebackDecision.legacyUid(account.id.toString(), it.id.toString())): it] }
            List<Map> resources = legacy.inventoryLegacyResources()
            if (resources.any { it == null }) throw new IllegalStateException('resource disappeared during inventory; retry the read-only inventory')
            List<Map> candidates = [], exclusions = []
            resources.each { Map resource ->
                Map task = byUid[resource.uid]
                String reason = null
                if (!task) reason = 'unrelated_or_unmapped_history'
                else if (!resource.etag || resource.etag.toString().startsWith('W/') || !resource.restorable) reason = 'unsupported_or_unversioned_resource'
                else if (resources.count { it.uid == resource.uid } != 1) reason = 'duplicate_legacy_identity'
                else if (ManagedEventIds.hasOwnershipMarker(resource.event?.description?.toString())) reason = 'already_planner_owned'
                if (reason) { exclusions << [resource: resource, reason: reason]; return }
                Task normalized = normalize(task)
                CalendarEvent expected = new RoutedProjections(config, calendar).fromTask(normalized)
                boolean disagreement = expected == null || expected.calendarName != resource.destination ||
                    resource.event.start != expected.start.toString() || resource.event.end != expected.end.toString() ||
                    resource.event.allDay != expected.allDay
                candidates << [resource: resource, taskId: task.id.toString(),
                    derivedUid: CalendarWritebackDecision.legacyUid(account.id.toString(), task.id.toString()),
                    unsyncedCalendarEdit: disagreement]
            }
            Map backups = backupState()
            Map manifest = [version: 1, inventoriedAt: clock.get().toString(), account: account,
                scope: scope(), routingFingerprint: config.routing.fingerprint, tasks: tasks,
                routing: [mode: 'legacy_rules', labelsToInclude: config.routing.labelsToInclude,
                    projectsToInclude: config.routing.projectsToInclude, rules: config.routing.rules],
                candidates: candidates, exclusions: exclusions, backupHash: MigrationState.hash(backups)]
            String hash = MigrationState.hash(manifest)
            Path export = state.directory.resolve('export-' + hash + '.json')
            MigrationState.writeJson(export, [manifest: manifest, backups: backups])
            state.save([version: 1, stage: 'INVENTORIED', manifest: manifest, manifestHash: hash,
                exportPath: export.toString(), deletions: [:], created: [:], restorations: [:], syncReleased: false])
            status()
        }
    }

    Map status() {
        Map current = state.read()
        if (current == null) return [stage: 'NOT_INVENTORIED']
        [stage: current.stage, manifestHash: current.manifestHash, exportPath: current.exportPath,
            scopeHash: MigrationState.hash(current.manifest.scope),
            candidates: current.manifest.candidates.size(), exclusions: current.manifest.exclusions.collect {
                [id: it.resource.id, destination: it.resource.destination, reason: it.reason]
            }, unsyncedCalendarEdits: current.manifest.candidates.findAll { it.unsyncedCalendarEdit }*.resource*.id,
            deletions: current.deletions, created: current.created.collectEntries { key, row -> [(key): row.outcome] },
            skipped: current.skipped ?: [], syncReleased: current.syncReleased == true,
            rollbackReviewHash: current.rollbackReviewHash]
    }

    /** Persist approval only after all resources, private backups and handoff evidence revalidate. */
    Map approveDeletion(Map approval) {
        store.withMutationLock {
            Map current = requireState(['INVENTORIED', 'DELETION_APPROVED'])
            requireApproval(current, approval, 'delete', current.manifestHash.toString())
            requireHeldWriters(approval)
            verifyBackup(current)
            requireNoPendingOperations()
            current.manifest.candidates.each { Map candidate ->
                Map row = candidate.resource
                Map live = legacy.readLegacyResource(row.destination.toString(), row.id.toString())
                if (live != row && !(live == null && current.deletions[key(row)] in ['unknown', 'absent'])) {
                    throw new IllegalStateException('reviewed candidate changed/missing before approval')
                }
            }
            List<String> disagreements = current.manifest.candidates.findAll { it.unsyncedCalendarEdit }*.resource*.id
            if ((approval.declinedCalendarEdits ?: []).toSet() != disagreements.toSet()) {
                throw new IllegalStateException('each known unsynced calendar edit must be explicitly declined, or reconcile and inventory again')
            }
            current.approval = approval
            current.stage = 'DELETION_APPROVED'
            state.save(current)
            status()
        }
    }

    Map deleteApproved() {
        store.withMutationLock {
            Map current = requireState(['DELETION_APPROVED', 'LEGACY_DELETED'])
            if (current.stage == 'LEGACY_DELETED') { requireLegacyAbsent(current); return status() }
            requireApproval(current, current.approval as Map, 'delete', current.manifestHash.toString())
            requireHeldWriters(current.approval as Map)
            verifyBackup(current)
            LegacyMigrationPermit permit = new LegacyMigrationPermit(state, current.manifestHash.toString(), 'delete', clock)
            // Preflight every surviving resource before the first dependent deletion.
            current.manifest.candidates.each { Map candidate ->
                Map row = candidate.resource
                Map live = legacy.readLegacyResource(row.destination.toString(), row.id.toString())
                if (live == null && current.deletions[key(row)] in ['unknown', 'absent']) return
                permit.requireUnchanged(row, live)
            }
            current.manifest.candidates.each { Map candidate ->
                Map row = candidate.resource
                String identity = key(row)
                Map live = legacy.readLegacyResource(row.destination.toString(), row.id.toString())
                if (live != null) {
                    permit.requireUnchanged(row, live)
                    current.deletions[identity] = 'unknown'; state.save(current)
                    legacy.deleteLegacyResource(row.destination.toString(), row.id.toString(), permit)
                } else if (!(current.deletions[identity] in ['unknown', 'absent'])) {
                    throw new IllegalStateException('unjournaled missing deletion requires review')
                }
                if (legacy.readLegacyResource(row.destination.toString(), row.id.toString()) != null) {
                    throw new IllegalStateException('legacy deletion is unverified; reconstruction remains held')
                }
                current.deletions[identity] = 'absent'; state.save(current)
            }
            requireLegacyAbsent(current)
            current.stage = 'LEGACY_DELETED'; state.save(current)
            status()
        }
    }

    Map reconstruct() {
        store.withMutationLock {
            Map current = requireState(['LEGACY_DELETED', 'RECONSTRUCTING'])
            requireHeldWriters(current.approval as Map)
            requireLegacyAbsent(current)
            if (current.stage == 'LEGACY_DELETED') {
                List<Map> fresh = todoist.fetchTasks()
                current.reconstructionTasks = fresh
                current.skipped = []
                fresh.each { Map raw ->
                    Task task = normalize(raw)
                    CalendarEvent event = new RoutedProjections(config, calendar).fromTask(task)
                    if (event == null) {
                        current.skipped << [taskId: task.id, reason: task.todoistDue == null ? 'unscheduled' :
                            (config.routing.includes(task) ? 'unroutable' : 'ineligible')]
                    } else {
                        if (calendar.findEventByUid(event.uid) != null) throw new IllegalStateException('preexisting planner identity requires separate reconciliation')
                        event = ProjectionMetadata.eventFromMap(ProjectionMetadata.eventMap(event) +
                            [id: calendar.newProjectionResourceId(event.calendarName, event.uid)])
                        current.created[key([destination: event.calendarName, id: event.id])] =
                            [target: ProjectionMetadata.eventMap(event), outcome: 'pending', taskId: task.id]
                    }
                }
                current.stage = 'RECONSTRUCTING'; state.save(current)
            }
            requireTasksUnchanged(current.reconstructionTasks as List<Map>)
            current.created.each { String identity, Map row ->
                requireLegacyAbsent(current)
                requireHeldWriters(current.approval as Map)
                requireTasksUnchanged(current.reconstructionTasks as List<Map>)
                CalendarEvent target = ProjectionMetadata.eventFromMap(row.target as Map)
                CalendarEvent live = calendar.readResource(target.calendarName, target.id)
                if (row.outcome == 'verified') {
                    ProjectionMetadata.requireUnchanged(ProjectionMetadata.eventFromMap(row.event as Map), live)
                } else if (row.outcome == 'unknown' && ProjectionReplacementService.matches(target, live)) {
                    ProjectionMetadata.requireUnchanged(live, calendar.findEventByUid(target.uid))
                } else {
                    if (live != null) throw new IllegalStateException('unexpected reconstruction resource; preserve human edit')
                    if (calendar.findEventByUid(target.uid) != null) throw new IllegalStateException('reconstruction identity collision')
                    row.outcome = 'unknown'; state.save(current)
                    live = calendar.createProjection(target)
                }
                if (!ProjectionReplacementService.matches(target, live) || !live.etag) {
                    throw new IllegalStateException('reconstruction readback mismatch')
                }
                row.event = ProjectionMetadata.eventMap(live); row.outcome = 'verified'; state.save(current)
            }
            requireTasksUnchanged(current.reconstructionTasks as List<Map>)
            status()
        }
    }

    Map verify() {
        store.withMutationLock {
            Map current = requireState(['RECONSTRUCTING', 'VERIFIED'])
            parity(current)
            if (current.syncReleased != true) {
                List<CalendarEvent> verified = current.created.values().collect { ProjectionMetadata.eventFromMap(it.event as Map) }
                store.commitProjections(verified)
            }
            current.stage = 'VERIFIED'; current.verifiedAt = clock.get().toString(); state.save(current)
            status()
        }
    }

    /** Release is permission, not a config/service toggle. Normal apply approval remains mandatory. */
    Map release(String authority, Map approval) {
        store.withMutationLock {
            Map current = requireState(['VERIFIED'])
            if (!(authority in ['sync', 'planning'])) throw new IllegalArgumentException('release sync or planning separately')
            requireApproval(current, approval, 'release-' + authority, current.manifestHash.toString())
            parity(current)
            if (authority == 'sync') { current.syncReleased = true; current.syncReleaseApproval = approval }
            else {
                if (current.syncReleased != true) throw new IllegalStateException('normal sync release must precede planning release')
                current.stage = 'PLANNING_RELEASED'; current.planningReleaseApproval = approval
            }
            state.save(current)
            status()
        }
    }

    /** Read-only live review; capture exact replacement versions before authorizing rollback. */
    Map reviewRollback() {
        store.withMutationLock {
            Map current = requireState(['DELETION_APPROVED', 'LEGACY_DELETED', 'RECONSTRUCTING', 'VERIFIED'])
            Map review = [manifestHash: current.manifestHash, created: current.created.collectEntries { String identity, Map row ->
                CalendarEvent target = ProjectionMetadata.eventFromMap(row.target as Map)
                CalendarEvent live = calendar.readResource(target.calendarName, target.id)
                if (live != null && (!ProjectionReplacementService.matches(target, live) ||
                    (row.event != null && row.event != ProjectionMetadata.eventMap(live)))) {
                    throw new IllegalStateException('replacement human edit blocks rollback; preserve and reconcile it')
                }
                [(identity): live == null ? null : ProjectionMetadata.eventMap(live)]
            }, legacy: current.manifest.candidates.collectEntries { Map candidate ->
                Map row = candidate.resource
                Map live = legacy.readLegacyResource(row.destination.toString(), row.id.toString())
                if (live != null && live != row) throw new IllegalStateException('legacy human edit blocks rollback')
                [(key(row)): live]
            }]
            current.rollbackReview = review; current.rollbackReviewHash = MigrationState.hash(review)
            state.save(current)
            status()
        }
    }

    Map rollback(Map approval) {
        store.withMutationLock {
            Map current = requireState(['DELETION_APPROVED', 'LEGACY_DELETED', 'RECONSTRUCTING', 'VERIFIED', 'ROLLING_BACK'])
            if (!current.rollbackReview || current.rollbackReviewHash != MigrationState.hash(current.rollbackReview)) {
                throw new IllegalStateException('fresh exact rollback review required')
            }
            requireApproval(current, approval, 'rollback', current.rollbackReviewHash.toString())
            requireHeldWriters(approval)
            verifyBackup(current)
            // Validate every surviving replacement before any removal/restoration.
            current.rollbackReview.created.each { String identity, Map expected ->
                Map row = current.created[identity]
                CalendarEvent target = ProjectionMetadata.eventFromMap(row.target as Map)
                CalendarEvent live = calendar.readResource(target.calendarName, target.id)
                if (live == null && row.rollback in ['unknown', 'absent']) return
                if ((live == null ? null : ProjectionMetadata.eventMap(live)) != expected) {
                    throw new IllegalStateException('replacement version drift blocks rollback')
                }
            }
            // A changed legacy resource must not cause removal of otherwise valid replacements.
            current.manifest.candidates.each { Map candidate ->
                Map row = candidate.resource
                String identity = key(row)
                Map live = legacy.readLegacyResource(row.destination.toString(), row.id.toString())
                Map restored = current.restorations[identity]?.outcome == 'verified' ? current.restorations[identity].resource : null
                if (restored != null) {
                    if (legacy.readLegacyResource(restored.destination.toString(), restored.id.toString()) != restored) {
                        throw new IllegalStateException('restored legacy version drift blocks rollback')
                    }
                } else if (current.restorations[identity]?.outcome == 'unknown' && live != null &&
                    live.uid == row.uid && live.payload == row.payload) {
                    // CalDAV may have accepted the original bytes before the response was lost.
                    // Its restore port performs full readback again; this does not authorize a new write.
                } else if (live != current.rollbackReview.legacy[identity]) {
                    throw new IllegalStateException('legacy version drift blocks rollback')
                }
            }
            current.stage = 'ROLLING_BACK'; current.rollbackApproval = approval; state.save(current)
            current.rollbackReview.created.each { String identity, Map expected ->
                Map row = current.created[identity]
                if (expected != null) {
                    CalendarEvent baseline = ProjectionMetadata.eventFromMap(expected)
                    CalendarEvent live = calendar.readResource(baseline.calendarName, baseline.id)
                    if (live != null) {
                        ProjectionMetadata.requireUnchanged(baseline, live)
                        row.rollback = 'unknown'; state.save(current)
                        calendar.retireProjection(baseline)
                    }
                    if (calendar.readResource(baseline.calendarName, baseline.id) != null) throw new IllegalStateException('replacement removal unverified')
                }
                row.rollback = 'absent'; state.save(current)
            }
            LegacyMigrationPermit permit = new LegacyMigrationPermit(state, current.manifestHash.toString(), 'restore', clock)
            current.manifest.candidates.each { Map candidate ->
                Map row = candidate.resource
                String identity = key(row)
                Map live = legacy.readLegacyResource(row.destination.toString(), row.id.toString())
                if (current.restorations[identity]?.outcome == 'verified') {
                    Map restored = current.restorations[identity].resource
                    permit.requireUnchanged(restored, legacy.readLegacyResource(restored.destination.toString(), restored.id.toString()))
                } else if (live != null && current.rollbackReview.legacy[identity] != null) {
                    permit.requireUnchanged(row, live)
                    current.restorations[identity] = [outcome: 'verified', resource: live]
                } else {
                    current.restorations[identity] = [outcome: 'unknown']; state.save(current)
                    Map restored = legacy.restoreLegacyResource(row.destination.toString(), row.id.toString(), permit)
                    current.restorations[identity] = [outcome: 'verified', resource: restored]
                }
                state.save(current)
            }
            // Do not restore snapshots/cursors or rewrite Todoist. Keep all planner authorities held.
            current.stage = 'ROLLED_BACK'; state.save(current)
            status()
        }
    }

    private void parity(Map current) {
        requireLegacyAbsent(current)
        if (current.syncReleased == true) { verifySyncedParity(); return }
        requireTasksUnchanged(current.reconstructionTasks as List<Map>)
        Map expected = [:]
        (current.reconstructionTasks as List<Map>).each { Map raw ->
            CalendarEvent event = new RoutedProjections(config, calendar).fromTask(normalize(raw))
            if (event != null) {
                Map row = current.created.values().find { it.taskId == raw.id.toString() }
                if (row == null) throw new IllegalStateException('reconstruction coverage mismatch')
                event = ProjectionMetadata.eventFromMap(ProjectionMetadata.eventMap(event) + [id: row.target.id])
                expected[key([destination: event.calendarName, id: event.id])] = ProjectionMetadata.eventMap(event)
            }
        }
        if (expected.keySet() != current.created.keySet()) throw new IllegalStateException('reconstruction coverage mismatch')
        current.created.each { String identity, Map row ->
            if (row.outcome != 'verified' || row.target != expected[identity]) throw new IllegalStateException('reconstruction incomplete')
            CalendarEvent baseline = ProjectionMetadata.eventFromMap(row.event as Map)
            ProjectionMetadata.requireUnchanged(baseline, calendar.readResource(baseline.calendarName, baseline.id))
            ProjectionMetadata.requireUnchanged(baseline, calendar.findEventByUid(baseline.uid))
        }
        List<Map> live = legacy.inventoryLegacyResources()
        Set<String> reviewed = current.created.keySet() as Set
        live.each { Map row ->
            if (row?.event && (ManagedEventIds.isPlannerUid(row.event.uid?.toString()) ||
                ManagedEventIds.hasOwnershipMarker(row.event.description?.toString())) && !reviewed.contains(key(row))) {
                throw new IllegalStateException('unexplained planner projection/duplicate blocks migration parity')
            }
        }
    }

    /** After normal sync release, a fresh common baseline replaces the migration's old task snapshot. */
    private void verifySyncedParity() {
        requireNoPendingOperations()
        List<Map> fresh = todoist.fetchTasks()
        Map expected = [:]
        fresh.each { Map raw ->
            CalendarEvent target = new RoutedProjections(config, calendar).fromTask(normalize(raw))
            if (target != null) expected[ProjectionMetadata.read(target).value.members[0].occurrenceKey] =
                [destination: target.calendarName, allDay: target.allDay,
                    member: ProjectionMetadata.read(target).value.members[0].findAll { name, value -> name != 'taskUpdated' }]
        }
        Map records = store.loadProjections()
        Set<String> covered = [] as Set
        records.each { String identity, Map record ->
            CalendarEvent baseline = ProjectionMetadata.eventFromMap(record.event as Map)
            CalendarEvent live = calendar.readResource(baseline.calendarName, baseline.id)
            if (record.status in ['retired', 'cancelled']) {
                if (live != null) throw new IllegalStateException('retired/cancelled resource blocks planning release')
                return
            }
            ProjectionMetadata.requireUnchanged(baseline, live)
            ProjectionMetadata.requireUnchanged(baseline, calendar.findEventByUid(baseline.uid))
            Map metadata = ProjectionMetadata.read(baseline).value
            if (metadata.routingFingerprint != config.routing.fingerprint) throw new IllegalStateException('synced routing mismatch')
            if (record.status == 'historical') return
            metadata.members.each { Map member ->
                Map schedule = [destination: baseline.calendarName, allDay: baseline.allDay,
                    member: member.findAll { name, value -> name != 'taskUpdated' }]
                if (!covered.add(member.occurrenceKey.toString()) || expected[member.occurrenceKey] != schedule) {
                    throw new IllegalStateException('fresh task/projection parity mismatch blocks planning release')
                }
            }
        }
        if (covered != expected.keySet()) throw new IllegalStateException('fresh projection coverage incomplete')
        legacy.inventoryLegacyResources().each { Map row ->
            if (row?.event && (ManagedEventIds.isPlannerUid(row.event.uid?.toString()) ||
                ManagedEventIds.hasOwnershipMarker(row.event.description?.toString())) &&
                !records.containsKey(ApplicationStateStore.projectionKey(ProjectionMetadata.eventFromMap(row.event as Map)))) {
                throw new IllegalStateException('unexplained owned resource blocks planning release')
            }
        }
        requireTasksUnchanged(fresh)
    }

    private Map requireState(List<String> stages) {
        Map current = state.read()
        if (current == null || !(current.stage in stages)) throw new IllegalStateException('migration stage does not authorize this operation')
        if (current.manifest.scope != scope() || current.manifest.routingFingerprint != config.routing.fingerprint ||
            current.manifest.account != todoist.fetchAccountIdentity()) {
            throw new IllegalStateException('migration account/routing/config drift; retain evidence and review')
        }
        current
    }

    private void requireApproval(Map current, Map approval, String action, String reviewHash) {
        Instant now = clock.get()
        if (approval == null || approval.action != action || approval.manifestHash != current.manifestHash ||
            approval.reviewHash != reviewHash || !integration.feedbackActors().contains(approval.actor?.toString()) ||
            !approval.approvedAt || !approval.expiresAt) throw new IllegalStateException('exact authorized migration approval required')
        Instant approved = Instant.parse(approval.approvedAt.toString()), expires = Instant.parse(approval.expiresAt.toString())
        if (approved.isAfter(now) || !expires.isAfter(now) || !expires.isAfter(approved) ||
            Duration.between(approved, expires) > Duration.ofHours(24)) {
            throw new IllegalStateException('migration review expired/invalid; renew exact-resource approval')
        }
    }

    private void requireHeldWriters(Map approval) {
        Map handoff = approval?.handoff instanceof Map ? approval.handoff as Map : [:]
        if (integration.daemon.enabled == true || integration.sync.enabled == true || config.calendarWritebackEnabled || config.recurrence.enabled ||
            handoff.legacyWritersStopped != true || handoff.otherMutationLoopsHeld != true ||
            handoff.scopeHash != MigrationState.hash(scope()) || !handoff.evidence || !handoff.checkedAt ||
            Instant.parse(handoff.checkedAt.toString()).isAfter(clock.get()) ||
            Duration.between(Instant.parse(handoff.checkedAt.toString()), clock.get()) > Duration.ofHours(24)) {
            throw new IllegalStateException('disabled planner loops and recent scope-bound operator writer-stop evidence required')
        }
    }

    private void requireNoPendingOperations() {
        if (store.loadSyncOperations().values().any { it.status != 'verified' }) throw new IllegalStateException('pending operation blocks migration')
    }

    private void requireLegacyAbsent(Map current) {
        current.manifest.candidates.each { Map candidate ->
            Map row = candidate.resource
            if (current.deletions[key(row)] != 'absent' || legacy.readLegacyResource(row.destination.toString(), row.id.toString()) != null ||
                calendar.findEventByUid(row.uid.toString()) != null) {
                throw new IllegalStateException('approved legacy deletion not verified absent; reconstruction/planning held')
            }
        }
    }

    private void requireTasksUnchanged(List<Map> expected) {
        if (expected == null || MigrationState.hash(expected.sort(false) { it.id }) !=
            MigrationState.hash(todoist.fetchTasks().sort(false) { it.id })) {
            throw new IllegalStateException('Todoist changed during reconstruction/parity; review fresh task evidence without restoring old fields')
        }
    }

    private Task normalize(Map raw) { Task.fromTodoistMap(raw, config.durationResolver, config.manualLabel, config.timezone) }
    private static String key(Map resource) { resource.destination + ':' + resource.id }

    private Map scope() {
        Map provider = [provider: integration.calendarProvider.provider,
            calendars: integration.calendars.collect { row ->
                Map identity = row.findAll { key, value -> key in ['name', 'id', 'url', 'role', 'managed_destination'] }
                if (row.auth instanceof Map) identity.auth = row.auth.findAll { key, value -> key in ['type', 'scheme', 'username', 'password_env', 'token_env'] }
                identity
            }, timezone: config.timezone.toString(), routingFingerprint: config.routing.fingerprint,
            defaultDurationMinutes: config.defaultDurationMinutes, durationLabels: config.durationLabels]
        if (integration.calendarProvider.usesGoogleCalendarApi()) provider.accountEmail = integration.calendarProvider.googleCalendarApi.accountEmail
        provider
    }

    private Map backupState() {
        Map units = [plans: integration.plansDir, applications: integration.applicationsDir,
            decisions: integration.decisionsDir, deliveries: integration.deliveriesDir]
        Map backups = [:]
        units.each { String unit, Path path ->
            Path root = path.toAbsolutePath().normalize()
            if (integration.calendarProvider.usesGoogleCalendarApi()) {
                def google = integration.calendarProvider.googleCalendarApi
                [google.oauthClientSecretFile, google.tokenStoreDir, google.qaTokenStoreDir].findAll { it != null }.each { Path secret ->
                    if (secret.toAbsolutePath().normalize().startsWith(root)) throw new IllegalStateException('state/credential directories overlap; refuse credential export')
                }
            }
            Map files = [:]
            if (Files.exists(root)) {
                Files.walk(root).withCloseable { paths -> paths.forEach { Path file ->
                    if (file.startsWith(state.directory.toAbsolutePath().normalize()) || file.fileName.toString().endsWith('.lock')) return
                    if (Files.isSymbolicLink(file)) throw new IllegalStateException('state backup refuses symlinks')
                    if (Files.isRegularFile(file)) files[root.relativize(file).toString()] = Base64.encoder.encodeToString(Files.readAllBytes(file))
                } }
            }
            backups[unit] = [files: files]
        }
        backups
    }

    private void verifyBackup(Map current) {
        Path file = Path.of(current.exportPath.toString())
        if (!Files.getPosixFilePermissions(file).every { it.name().startsWith('OWNER_') }) throw new IllegalStateException('migration export must be private')
        Map exported = MigrationState.readJson(file)
        if (exported.manifest != current.manifest || MigrationState.hash(exported.backups) != current.manifest.backupHash ||
            exported.backups.keySet() != ['plans', 'applications', 'decisions', 'deliveries'] as Set) {
            throw new IllegalStateException('complete verified manifest/state backup required')
        }
        exported.backups.values().each { unit -> unit.files.values().each { Base64.decoder.decode(it.toString()) } }
    }
}
