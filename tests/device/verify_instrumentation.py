#!/usr/bin/env python3
"""Exit nonzero on absent, truncated or unsuccessful instrumentation."""
import re
import sys
from pathlib import Path

SMOKE = "app.droidnative.test.DebianSmokeTest"

def verify(log: str, adb_status: int) -> None:
    statuses = [int(x) for x in re.findall(r"^INSTRUMENTATION_STATUS_CODE: (-?\d+)\s*$", log, re.M)]
    terminal = re.findall(r"^INSTRUMENTATION_CODE: (-?\d+)\s*$", log, re.M)
    if adb_status != 0:
        raise ValueError(f"adb exited {adb_status}")
    if re.search(r"^INSTRUMENTATION_(?:FAILED|ABORTED):|^INSTRUMENTATION_RESULT: shortMsg=|^FAILURES!!!", log, re.M):
        raise ValueError("Instrumentation reported an error")
    if terminal != ["-1"] or statuses != [1, 0]:
        raise ValueError(f"Expected one started and passed test and a final result, got {statuses} / {terminal}")
    if not re.search(rf"^INSTRUMENTATION_STATUS: class={re.escape(SMOKE)}\s*$", log, re.M):
        raise ValueError("Missing Galaxunil smoke identity")
    if not re.search(r"^OK \(1 test\)\s*$", log, re.M):
        raise ValueError("JUnit summary did not confirm a single successful test")

if __name__ == "__main__":
    try:
        verify(Path(sys.argv[1]).read_text(errors="replace"), int(sys.argv[2]))
    except (IndexError, OSError, ValueError) as exc:
        sys.exit(str(exc))
    print("PASS: Galaxunil Debian device smoke")
