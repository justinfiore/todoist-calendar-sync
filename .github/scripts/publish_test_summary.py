#!/usr/bin/env python3
"""Publish Gradle JUnit XML results to a GitHub Actions check summary.

The workflow intentionally keeps this parser in-repo rather than relying on a
third-party test-reporting action. On failure it lists each failing test with
its suite and error detail directly in the check's Summary tab.
"""

from __future__ import annotations

import os
import sys
import xml.etree.ElementTree as ET
from pathlib import Path


def markdown_cell(value: str) -> str:
    return value.replace("|", "\\|").replace("\n", " ").strip()


def main() -> int:
    results_dir = Path(sys.argv[1] if len(sys.argv) > 1 else "app/build/test-results/test")
    summary_path = os.environ.get("GITHUB_STEP_SUMMARY")
    if not summary_path:
        print("GITHUB_STEP_SUMMARY is not set; no GitHub summary to publish.")
        return 0

    suites = []
    failures = []
    totals = {"tests": 0, "failures": 0, "errors": 0, "skipped": 0}

    for report in sorted(results_dir.glob("TEST-*.xml")):
        root = ET.parse(report).getroot()
        suite_name = root.attrib.get("name", report.stem)
        suite_counts = {key: int(root.attrib.get(key, "0")) for key in totals}
        totals = {key: totals[key] + suite_counts[key] for key in totals}
        suites.append((suite_name, suite_counts))

        for case in root.findall("testcase"):
            failure = case.find("failure")
            problem = failure if failure is not None else case.find("error")
            if problem is not None:
                failures.append(
                    (
                        suite_name,
                        case.attrib.get("name", "unnamed test"),
                        problem.attrib.get("message", problem.tag),
                    )
                )

    lines = ["## Test results", ""]
    if not suites:
        lines.extend([
            "> [!WARNING]",
            f"No JUnit XML reports were found in `{results_dir}`.",
            "",
        ])
    else:
        outcome = "✅ Passed" if totals["failures"] + totals["errors"] == 0 else "❌ Failed"
        lines.extend([
            f"**{outcome}** — {totals['tests']} tests; "
            f"{totals['failures']} failures; {totals['errors']} errors; {totals['skipped']} skipped.",
            "",
            "| Suite | Tests | Failures | Errors | Skipped |",
            "| --- | ---: | ---: | ---: | ---: |",
        ])
        for suite_name, counts in suites:
            lines.append(
                f"| `{markdown_cell(suite_name)}` | {counts['tests']} | {counts['failures']} | "
                f"{counts['errors']} | {counts['skipped']} |"
            )
        lines.append("")

    if failures:
        lines.extend([
            "### Failing tests",
            "",
            "| Suite | Test | Failure |",
            "| --- | --- | --- |",
        ])
        for suite_name, test_name, message in failures:
            lines.append(
                f"| `{markdown_cell(suite_name)}` | `{markdown_cell(test_name)}` | "
                f"{markdown_cell(message)[:500]} |"
            )
        lines.append("")

    with Path(summary_path).open("a", encoding="utf-8") as summary:
        summary.write("\n".join(lines))

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
