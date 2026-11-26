#!/usr/bin/env python3
"""Prints the executed test totals from the Gradle JUnit XML reports so the
number of tests that ran (and passed) is verifiable from the Actions log."""
import glob
import os
import sys
import xml.etree.ElementTree as ET

results_dir = os.path.join("app", "build", "test-results", "testDebugUnitTest")
files = sorted(glob.glob(os.path.join(results_dir, "*.xml")))
if not files:
    print("PRUEBAS: sin resultados XML (tarea sin ejecutar).")
    sys.exit(0)

total = failed = errors = skipped = 0
suites = 0
for path in files:
    try:
        root = ET.parse(path).getroot()
    except ET.ParseError as exc:
        print(f"PRUEBAS: XML ilegible {os.path.basename(path)}: {exc}")
        sys.exit(1)
    suites += 1
    total += int(root.get("tests", "0"))
    failed += int(root.get("failures", "0"))
    errors += int(root.get("errors", "0"))
    skipped += int(root.get("skipped", "0"))

passed = total - failed - errors - skipped
print(f"PRUEBAS: {total} ejecutadas · {passed} OK · {failed} fallidas · {errors} errores · {skipped} omitidas · {suites} suites")
for path in files:
    root = ET.parse(path).getroot()
    if int(root.get("failures", "0")) or int(root.get("errors", "0")):
        for case in root.iter("testcase"):
            if case.find("failure") is not None or case.find("error") is not None:
                print(f"  FALLIDA: {root.get('name')}.{case.get('name')}")
