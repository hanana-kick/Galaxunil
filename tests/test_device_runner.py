"""Integration controls for the device runner; no adb or Android device is used."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

RUN = Path(__file__).parent / "device" / "run.sh"
PKG = "io.github.hananakick.galaxunil"

ADB = '''#!/bin/bash
set -eu
shift 2
case "$*" in
 "shell getprop ro.kernel.qemu"|"shell getprop sys.boot_completed") echo 1 ;;
 "shell pm list packages "*) if [[ "$MODE" == stale ]]; then echo "package:io.github.hananakick.galaxunil"; fi ;;
 "install "*) echo Success ;;
 "shell pm list instrumentation")
   if [[ "$MODE" != wrongrunner ]]; then
     echo 'instrumentation:io.github.hananakick.galaxunil.test/androidx.test.runner.AndroidJUnitRunner (target=io.github.hananakick.galaxunil)'
   fi ;;
 "shell am instrument "*) if [[ "$MODE" == truncated ]]; then echo 'INSTRUMENTATION_STATUS_CODE: 1'; exit 0; fi
   printf 'INSTRUMENTATION_STATUS: class=app.droidnative.test.DebianSmokeTest\\nINSTRUMENTATION_STATUS_CODE: 1\\nINSTRUMENTATION_STATUS_CODE: 0\\nOK (1 test)\\nINSTRUMENTATION_CODE: -1\\n' ;;
 *) exit 8 ;;
esac
'''

class DeviceRunnerTests(unittest.TestCase):
    def smoke(self, mode, serial="emulator-5554"):
        with tempfile.TemporaryDirectory() as temp:
            t = Path(temp)
            bin = t / "bin"; bin.mkdir()
            sdk = t / "sdk"; bt = sdk / "build-tools" / "37.0.0"; bt.mkdir(parents=True)
            def executable(path, text):
                path.write_text(text)
                path.chmod(0o755)
            executable(bin / "adb", ADB)
            executable(bt / "aapt2", f'''#!/bin/bash
if [[ "$3" == *test.apk ]]; then
  echo "package: name='{PKG}.test'"
else
  echo "package: name='{PKG}'"
fi
''')
            env = os.environ | {"MODE": mode, "ANDROID_HOME": str(sdk), "TMPDIR": temp, "PATH": f"{bin}:{os.environ['PATH']}"}
            result = subprocess.run(["bash", str(RUN), serial, "app.apk", "test.apk"],
                                    env=env, capture_output=True, text=True)
            return result

    def test_accepted(self):
        r = self.smoke("valid")
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertIn("PASS: Galaxunil", r.stdout)

    def test_identity_and_results_fail_closed(self):
        for scenario in ("wrongrunner", "truncated", "stale"):
            with self.subTest(scenario=scenario):
                self.assertNotEqual(self.smoke(scenario).returncode, 0)

    def test_physical_serial_rejected(self):
        self.assertNotEqual(self.smoke("valid", "R850XY").returncode, 0)
