package todoistcaldavsync.planner.writeback

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.nio.file.*
import java.time.Instant
import todoistcaldavsync.planner.domain.CalendarEvent

/** Separate from both Todoist cursors. Callers save a feed state only after classification. */
final class CalendarWritebackStore {
    static final String FILENAME = 'calendar-writeback.json'
    final Path path

    CalendarWritebackStore(Path directory) { path = directory.resolve(FILENAME) }

    synchronized Map load() {
        Files.exists(path) ? new JsonSlurper().parse(path.toFile()) as Map : [calendars: [:], echoes: [:]]
    }

    synchronized Map calendar(String name) { new LinkedHashMap(load().calendars[name] ?: [:]) }

    synchronized Map echo(String calendar, String uid) { load().echoes[calendar + '\n' + uid] as Map ?: [:] }

    synchronized void saveCalendar(String name, Map state) {
        Map all = load()
        all.calendars[name] = state
        save(all)
    }

    synchronized void recordWrite(CalendarEvent event, Instant at) {
        Map all = load()
        all.echoes[event.calendarName + '\n' + event.uid] = [etag: event.etag,
            start: event.start?.toString(), end: event.end?.toString(), writtenAt: at.toString(), writeId: UUID.randomUUID().toString()]
        save(all)
    }

    private void save(Map all) {
        Files.createDirectories(path.parent)
        Path temporary = Files.createTempFile(path.parent, '.calendar-writeback-', '.tmp')
        try {
            Files.writeString(temporary, JsonOutput.prettyPrint(JsonOutput.toJson(all)))
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { Files.deleteIfExists(temporary) }
    }
}
