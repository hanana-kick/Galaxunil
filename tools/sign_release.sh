#!/usr/bin/env bash
set -euo pipefail
umask 077
for key in GALAXUNIL_KEYSTORE_BASE64 GALAXUNIL_KEY_ALIAS GALAXUNIL_STORE_PASSWORD GALAXUNIL_KEY_PASSWORD; do
  if [[ -z "${!key:-}" ]]; then
    printf 'Release signing secret missing: %s\n' "$key" >&2
    exit 2
  fi
done
sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:?Set ANDROID_HOME or ANDROID_SDK_ROOT}}/build-tools/37.0.0"
apk="app/build/outputs/apk/play/release/app-play-release-unsigned.apk"
test -s "$apk"
work="$(mktemp -d)"
trap 'rm -rf -- "$work"' EXIT
printf '%s' "$GALAXUNIL_KEYSTORE_BASE64" | base64 --decode > "$work/release-keystore"
unset GALAXUNIL_KEYSTORE_BASE64
mkdir -p dist-release
"$sdk/zipalign" -c 4 "$apk"
"$sdk/apksigner" sign --ks "$work/release-keystore" --ks-key-alias "$GALAXUNIL_KEY_ALIAS" \
  --ks-pass env:GALAXUNIL_STORE_PASSWORD --key-pass env:GALAXUNIL_KEY_PASSWORD \
  --out dist-release/Galaxunil.apk "$apk"
"$sdk/apksigner" verify --verbose --print-certs dist-release/Galaxunil.apk
(cd dist-release && sha256sum Galaxunil.apk > SHA256SUMS)
