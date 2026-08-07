package todoistcaldavsync.planner

import groovy.cli.picocli.CliBuilder
import todoistcaldavsync.planner.adapters.FixtureCalendarGateway
import todoistcaldavsync.planner.adapters.FixtureTodoistGateway
import todoistcaldavsync.planner.config.PlannerConfig
import todoistcaldavsync.planner.domain.Task
import todoistcaldavsync.planner.report.CapacityReportFormatter
import todoistcaldavsync.planner.report.CapacityReportService

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Read-only planner CLI. Phase 1 supports --mode capacity-report only.
 * No mutation gateway or write path is constructed.
 */
class PlannerCli {

    static void main(String[] args) {
        int code = run(args)
        if (code != 0) {
            System.exit(code)
        }
    }

    /**
     * @return exit code (0 success)
     */
    static int run(String[] args, Appendable out = System.out, Appendable err = System.err) {
        def cli = new CliBuilder(usage: 'PlannerCli --mode capacity-report --config FILE [options]', stopAtNonOption: false)
        cli.h(longOpt: 'help', 'Show help')
        cli._(longOpt: 'mode', args: 1, argName: 'mode', 'Mode: capacity-report')
        cli._(longOpt: 'format', args: 1, argName: 'format', 'Output format: markdown|json (default markdown)')
        cli._(longOpt: 'config', args: 1, argName: 'file', 'Planner YAML config file')
        cli._(longOpt: 'tasks', args: 1, argName: 'file', 'Todoist tasks fixture (JSON/YAML)')
        cli._(longOpt: 'events', args: 1, argName: 'file', 'Calendar events fixture (JSON/YAML)')
        cli._(longOpt: 'range-start', args: 1, argName: 'instant', 'Range start ISO-8601 instant or date')
        cli._(longOpt: 'range-end', args: 1, argName: 'instant', 'Range end ISO-8601 instant or date (exclusive)')

        def options = cli.parse(args)
        if (!options) {
            return 2
        }
        if (options.h) {
            cli.usage(out)
            return 0
        }

        def mode = options.mode?.toString()
        if (!mode) {
            err.append("Error: --mode is required\n")
            cli.usage(err)
            return 2
        }
        if (mode != 'capacity-report') {
            err.append("Error: unsupported mode '${mode}'. Phase 1 supports only capacity-report.\n")
            return 2
        }

        def format = (options.format ?: 'markdown').toString().toLowerCase()
        if (!(format in ['markdown', 'json'])) {
            err.append("Error: --format must be markdown or json\n")
            return 2
        }

        if (!options.config) {
            err.append("Error: --config is required\n")
            return 2
        }
        if (!options.tasks) {
            err.append("Error: --tasks fixture is required for capacity-report\n")
            return 2
        }
        if (!options.events) {
            err.append("Error: --events fixture is required for capacity-report\n")
            return 2
        }

        try {
            PlannerConfig config = PlannerConfig.load(new File(options.config.toString()))
            def todoistGw = FixtureTodoistGateway.fromFile(new File(options.tasks.toString()))
            // Date-only / zone-less fixture events use planner timezone
            def calendarGw = FixtureCalendarGateway.fromFile(new File(options.events.toString()), config.timezone)

            // Explicit: only read gateways — no write path
            CapacityReportService service = new CapacityReportService(config, todoistGw, calendarGw)

            ZoneId zone = config.timezone
            Instant rangeStart = parseRangeBound(optionString(options, 'range-start'), zone)
            Instant rangeEnd = parseRangeBound(optionString(options, 'range-end'), zone)
            // Default missing bounds: today through +3 local days
            LocalDate today = LocalDate.now(zone)
            if (rangeStart == null) {
                rangeStart = today.atStartOfDay(zone).toInstant()
            }
            if (rangeEnd == null) {
                rangeEnd = today.plusDays(3).atStartOfDay(zone).toInstant()
            }
            if (!rangeEnd.isAfter(rangeStart)) {
                err.append("Error: --range-end must be after --range-start (got start=${rangeStart}, end=${rangeEnd})\n")
                return 2
            }

            def report = service.generate(rangeStart, rangeEnd)
            String rendered = format == 'json'
                ? CapacityReportFormatter.toJson(report)
                : CapacityReportFormatter.toMarkdown(report)
            out.append(rendered)
            if (!rendered.endsWith('\n')) {
                out.append('\n')
            }
            return 0
        } catch (IllegalArgumentException e) {
            err.append("Error: ${e.message}\n")
            return 2
        } catch (Exception e) {
            err.append("Error: ${e.message}\n")
            return 1
        }
    }

    /**
     * CliBuilder returns false for absent long options — normalize to null/string.
     */
    private static String optionString(def options, String name) {
        def v = options.getProperty(name)
        if (v == null || v == false || v == true) {
            return null
        }
        def s = v.toString()
        if (!s || s == 'false' || s == 'true') {
            return null
        }
        return s
    }

    /**
     * Date inputs resolve to local midnight in config timezone (range end remains exclusive).
     */
    private static Instant parseRangeBound(String value, ZoneId zone) {
        if (!value) {
            return null
        }
        def v = value.trim()
        if (v.matches(/^\d{4}-\d{2}-\d{2}$/)) {
            return LocalDate.parse(v).atStartOfDay(zone).toInstant()
        }
        return Task.parseFlexibleInstant(v, false, zone)
    }
}
