#!/usr/bin/env python3
"""Expose Kotlin and Gradle failures in the GitHub Actions check annotations."""

import os
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path
from urllib.parse import unquote, urlparse


def escape(value):
    return value.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")


def property_value(value):
    return escape(value).replace(":", "%3A").replace(",", "%2C")


def report(log_path):
    lines = Path(log_path).read_text(encoding="utf-8", errors="replace").splitlines()
    workspace = Path(os.environ.get("GITHUB_WORKSPACE", ".")).resolve()
    kotlin_error = re.compile(r"^e: (.+?):(\d+):(\d+)\s+(.+)$")
    count = 0

    for line in lines:
        match = kotlin_error.match(line)
        if not match:
            continue
        filename, line_number, column, message = match.groups()
        if filename.startswith("file://"):
            filename = unquote(urlparse(filename).path)
        path = Path(filename)
        try:
            filename = str(path.relative_to(workspace)) if path.is_absolute() else str(path)
        except ValueError:
            # Do not attach an external file to this repository's check run.
            filename = ""
        location = f"file={property_value(filename)},line={line_number},col={column}" if filename else ""
        print(f"::error {location}::{escape(message)}" if location else f"::error::{escape(message)}")
        count += 1
        if count == 40:
            break

    # Include JUnit failures in annotations even when CI log downloads are unavailable.
    for report_path in workspace.glob("app/build/test-results/testDebugUnitTest/TEST-*.xml"):
        try:
            root = ET.parse(report_path).getroot()
            for case in root.findall("testcase"):
                for failure in case.findall("failure"):
                    message = f"{case.get('classname')}.{case.get('name')}: {failure.get('message', '')}"
                    print(f"::error::{escape(message[:3000])}")
        except (OSError, ET.ParseError):
            pass

    # Dependency resolution and Gradle configuration errors may not have source locations.
    for index, line in enumerate(lines):
        if line.strip() == "* What went wrong:":
            details = []
            for detail in lines[index + 1:]:
                if detail.startswith("* Try:") or detail.startswith("* Exception is:"):
                    break
                if detail.strip():
                    details.append(detail.strip())
                if len(details) == 12:
                    break
            if details:
                print(f"::error::{escape(' '.join(details)[:4000])}")
            break


if __name__ == "__main__":
    report(sys.argv[1])
