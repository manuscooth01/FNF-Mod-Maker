#!/usr/bin/env python3
"""Build a markdown report of failed unit tests from JUnit XML results.

Printed as one line per failure plus totals, so CI can publish the exact
test names, messages and file/line locations into the run annotations and
a tracking issue without publishing any build artifact.
"""

import sys
import xml.etree.ElementTree as ET
from pathlib import Path


def main() -> None:
    workspace = Path(".")
    reports = sorted(workspace.glob("app/build/test-results/testDebugUnitTest/TEST-*.xml"))
    total = failures = errors = skipped = 0
    lines: list[str] = []

    for report_path in reports:
        try:
            root = ET.parse(report_path).getroot()
        except (OSError, ET.ParseError):
            continue
        total += int(root.get("tests", "0"))
        failures += int(root.get("failures", "0"))
        errors += int(root.get("errors", "0"))
        skipped += int(root.get("skipped", "0"))
        for case in root.findall("testcase"):
            for failure in case.findall("failure") + case.findall("error"):
                classname = case.get("classname", "?")
                name = case.get("name", "?")
                message = (failure.get("message") or "").strip().replace("\n", " ")
                location = ""
                # The failure body usually starts with the exception stack trace;
                # keep the first app frame so the report names file and line.
                body = (failure.text or "").strip()
                for frame in body.splitlines():
                    frame = frame.strip()
                    if frame.startswith("at com.fnfmodmaker."):
                        location = frame
                        break
                entry = f"- `{classname}.{name}` — {message[:300]}"
                if location:
                    entry += f"\n  - `{location[:200]}`"
                lines.append(entry)

    print(f"**Pruebas ejecutadas:** {total} · **Fallidas:** {failures} · "
          f"**Errores:** {errors} · **Omitidas:** {skipped}")
    print()
    if lines:
        print("### Pruebas fallidas")
        print()
        for line in lines[:120]:
            print(line)
        if len(lines) > 120:
            print(f"- … y {len(lines) - 120} fallos más")
    else:
        print("No se encontraron fallos en los reportes JUnit.")


if __name__ == "__main__":
    main()
