import importlib.util
from pathlib import Path
import unittest

PATH = Path(__file__).parent / "device" / "verify_instrumentation.py"
spec = importlib.util.spec_from_file_location("verify_instrumentation", PATH)
check = importlib.util.module_from_spec(spec)
spec.loader.exec_module(check)

OK = f"""INSTRUMENTATION_STATUS: class={check.SMOKE}
INSTRUMENTATION_STATUS_CODE: 1
INSTRUMENTATION_STATUS_CODE: 0
INSTRUMENTATION_RESULT: stream=
OK (1 test)
INSTRUMENTATION_CODE: -1
"""

class DeviceReceiptTest(unittest.TestCase):
    def test_one_success(self):
        check.verify(OK, 0)

    def test_missing_runner(self):
        with self.assertRaises(ValueError):
            check.verify("INSTRUMENTATION_FAILED: runner not found", 0)

    def test_truncated(self):
        with self.assertRaises(ValueError):
            check.verify(OK.split("INSTRUMENTATION_RESULT:")[0], 0)

    def test_no_tests(self):
        with self.assertRaises(ValueError):
            check.verify("OK (0 tests)\nINSTRUMENTATION_CODE: -1\n", 0)

    def test_all_skipped(self):
        with self.assertRaises(ValueError):
            check.verify(OK.replace("CODE: 0", "CODE: -3"), 0)

    def test_failed(self):
        with self.assertRaises(ValueError):
            check.verify(OK.replace("CODE: 0", "CODE: -2"), 0)

    def test_wrong_identity(self):
        with self.assertRaises(ValueError):
            check.verify(OK.replace(check.SMOKE, "app.other.Test"), 0)

    def test_adb_exit_overrules_output(self):
        with self.assertRaises(ValueError):
            check.verify(OK, 124)
