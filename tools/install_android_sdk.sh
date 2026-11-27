#!/usr/bin/env bash
# Exact Google SDK archives for Linux CI runners that do not ship sdkmanager.
set -euo pipefail
: "${ANDROID_HOME:?Set a dedicated Android SDK path}"
work="$(mktemp -d)"
trap 'rm -rf -- "$work"' EXIT
fetch() { curl --proto '=https' --proto-redir '=https' -fsSL --retry 2 --max-time 160 \
  "https://dl.google.com/android/repository/$1" -o "$work/$2"; }
fetch platform-37.0_r02.zip platform.zip
fetch build-tools_r37_linux.zip build-tools.zip
(cd "$work"; sha256sum -c <<'PINS'
840b23e827f96e64aea4c89a1194aac3dc5f6bad37edb231c5c795d890330e8d  platform.zip
01af179347cbcd9c208b7f8171f7b21f6dd1d2f85bcd15e88caa51d5d7b86060  build-tools.zip
PINS
)
unzip -q "$work/platform.zip" -d "$work/platform"
unzip -q "$work/build-tools.zip" -d "$work/build-tools"
mkdir -p "$ANDROID_HOME/platforms" "$ANDROID_HOME/build-tools"
test ! -e "$ANDROID_HOME/platforms/android-37.0"
test ! -e "$ANDROID_HOME/build-tools/37.0.0"
mv "$work/platform/android-37.0" "$ANDROID_HOME/platforms/"
mv "$work/build-tools/android-37.0" "$ANDROID_HOME/build-tools/37.0.0"
cat > "$ANDROID_HOME/platforms/android-37.0/package.xml" <<'XML'
<?xml version="1.0" encoding="UTF-8"?>
<common:repository xmlns:common="http://schemas.android.com/repository/android/common/02" xmlns:sdk="http://schemas.android.com/sdk/android/repo/repository2/03" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
  <localPackage path="platforms;android-37.0">
    <type-details xsi:type="sdk:platformDetailsType">
      <api-level>37.0</api-level>
      <extension-level>22</extension-level>
      <base-extension>true</base-extension>
      <layoutlib api="15"/>
    </type-details>
    <revision><major>2</major></revision>
    <display-name>Android SDK Platform 37.0</display-name>
  </localPackage>
</common:repository>
XML
test -s "$ANDROID_HOME/platforms/android-37.0/android.jar"
"$ANDROID_HOME/build-tools/37.0.0/aapt2" version
