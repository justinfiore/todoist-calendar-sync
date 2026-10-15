package todoistcaldavsync.planner.writeback

import spock.lang.Specification
import todoistcaldavsync.planner.domain.CalendarEvent

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

class CalendarWritebackStoreSpec extends Specification {
    def "missing file means no calendar cursor or echo and uses a separate filename"() {
        given:
        Path directory = Files.createTempDirectory('calendar-writeback-missing-')
        def store = new CalendarWritebackStore(directory)

        expect:
        store.path == directory.resolve('calendar-writeback.json')
        CalendarWritebackStore.FILENAME == 'calendar-writeback.json'
        !Files.exists(store.path)
        store.load() == [calendars: [:], echoes: [:]]
        store.calendar('Planned') == [:]
        store.echo('Planned', 'uid-1') == [:]
        !Files.exists(directory.resolve('.state'))
        !Files.exists(directory.resolve('todoist-items-sync.json'))
    }

    def "calendar cursor and collection state survive a file reload without clobbering other calendars"() {
        given:
        Path directory = Files.createTempDirectory('calendar-writeback-roundtrip-')
        def store = new CalendarWritebackStore(directory)

        when:
        store.saveCalendar('Planned', [cursor: 'cursor-2', collectionTag: 'ctag-7',
                                       syncSupported: true, stamps: ['uid-1': 'etag-1']])
        store.saveCalendar('Busy', [cursor: 'cursor-busy'])
        def reloaded = new CalendarWritebackStore(directory)

        then:
        Files.isRegularFile(directory.resolve(CalendarWritebackStore.FILENAME))
        reloaded.calendar('Planned') == [cursor: 'cursor-2', collectionTag: 'ctag-7',
                                         syncSupported: true, stamps: ['uid-1': 'etag-1']]
        reloaded.calendar('Busy') == [cursor: 'cursor-busy']
        reloaded.load().echoes == [:]
    }

    def "recorded write reloads as an echo token usable by the shared decision"() {
        given:
        Path directory = Files.createTempDirectory('calendar-writeback-echo-')
        Instant start = Instant.parse('2026-10-15T10:00:00Z')
        Instant end = Instant.parse('2026-10-15T11:00:00Z')
        Instant writtenAt = Instant.parse('2026-10-15T10:05:00Z')
        CalendarEvent event = CalendarEvent.builder().id('event-1').uid('uid-1').title('Task')
            .calendarName('Planned').start(start).end(end).lastModified(writtenAt).etag('etag-1').build()
        def store = new CalendarWritebackStore(directory)

        when:
        store.recordWrite(event, writtenAt)
        Map echo = new CalendarWritebackStore(directory).echo('Planned', 'uid-1')

        then:
        echo.etag == 'etag-1'
        echo.start == start.toString()
        echo.end == end.toString()
        echo.writtenAt == writtenAt.toString()
        echo.writeId ==~ /[0-9a-f-]{36}/
        CalendarWritebackDecision.echo(event, echo)
        new CalendarWritebackStore(directory).echo('Other', 'uid-1') == [:]
    }
}
