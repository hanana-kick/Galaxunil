#!/usr/bin/env bash
# Usage: tests/device/run.sh emulator-5554 [app.apk test.apk]
# Runs Galaxunil smoke on a fresh dedicated emulator. Refuses physical devices
# and existing Galaxunil installations. Never clears device/app data.
set -euo pipefail
root="$(cd "$(dirname "$0")/../.." && pwd)"
serial="${1:?Specify an explicit emulator-NNNN serial}"
[[ $serial =~ ^emulator-[0-9]+$ ]] && [[ $# == 1 || $# == 3 ]] || exit 2
aptarget=io.github.hananakick.galaxunil
adb_arr=(adb -s "$serial")
adb_run() { timeout 120 "${adb_arr[@]}" "$@"; }
test "$(adb_run shell getprop ro.kernel.qemu | tr -d '\r')" = 1
test "$(adb_run shell getprop sys.boot_completed | tr -d '\r')" = 1
existing="$(adb_run shell pm list packages "$aptarget" | tr -d '\r')"
if grep -Fxq "package:$aptarget" <<< "$existing"; then
  echo 'Refusing to run on an emulator with Galaxunil already installed.' >&2
  exit 2
fi
if [[ $# == 1 ]]; then
  (cd "$root"; python3 tools/bootstrap_assets.py; ./gradlew :app:assemblePlayDebug :app:assemblePlayDebugAndroidTest)
fi
apk="${2:-$root/app/build/outputs/apk/play/debug/app-play-debug.apk}"
test_apk="${3:-$root/app/build/outputs/apk/androidTest/play/debug/app-play-debug-androidTest.apk}"
sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:?Set ANDROID_HOME or ANDROID_SDK_ROOT}}"
aapt="$sdk/build-tools/37.0.0/aapt2"
id() { "$aapt" dump badging "$1" | sed -n "s/^package: name='\([^']*\)'.*/\1/p"; }
pkg="$(id "$apk")"
test_pkg="$(id "$test_apk")"
test "$pkg" = "$aptarget"
test "$test_pkg" = "$pkg.test"
out="$(mktemp -d -p "${TMPDIR:-/tmp}" galaxunil-device.XXXXXX)"
echo "Test receipts: $out"
adb_run install "$apk" | tee "$out/install-app.txt"
adb_run install "$test_apk" | tee "$out/install-test.txt"
grep -Fxq Success "$out/install-app.txt"
grep -Fxq Success "$out/install-test.txt"
runner="$test_pkg/androidx.test.runner.AndroidJUnitRunner"
adb_run shell pm list instrumentation | tr -d '\r' > "$out/runners.txt"
grep -Fxq "instrumentation:$runner (target=$pkg)" "$out/runners.txt"
set +e
timeout 420 "${adb_arr[@]}" shell am instrument -w -r -e class app.droidnative.test.DebianSmokeTest "$runner" \
  2>&1 | tr -d '\r' | tee "$out/instrument.txt"
rc=${PIPESTATUS[0]}
set -e
python3 "$root/tests/device/verify_instrumentation.py" "$out/instrument.txt" "$rc"
