# DroidNative Debian preview

This is a separate Android preview with application ID `app.droidnative.debian`. It has private Android data and a separate Debian 13 rootfs; it is not an update to the Void preview. From the Overview tab, set up Linux, then use Install to browse and filter Debian AppStream packages or search by name. Firefox ESR is first. Installation uses Debian signed APT repositories only. An installed package's .desktop entry is verified against dpkg's installed-package file list before Android hands the app off to the Linux compositor. This preview uses Wayland; not every Debian GUI package has a compatible Wayland backend. No Flatpak, Snap, or AppImage catalog is enabled in this preview.

GPU preferences retain the upstream native graphics transport with Default, VirGL and llvmpipe modes. No driver or browser-security flags are set to bypass a failing browser sandbox. VirGL selection is not evidence of physical GPU acceleration. Verify actual WebGL/GLES renderer and compare with llvmpipe on real hardware.

## Verification and inputs

Reference DroidNative source: `fa591010d6e33adbf5a79178b874fd4abdc58931`, beta-0.1. The pinned, unchanged native package is described in CATALOG.md; distro-specific instructions in CATALOG.md describe the separate Void build. Debian 13 rootfs assets are byte-for-byte OCI layer contents after recompression (see debian-provenance/oci-receipts.json for image/config/layer digests). The embedded public CA bundle SHA-256 is `e9c7b63ee2623386e9b0a3ee32f499d01a8ea23dc83b8383ddff9f8e749543b7`, extracted from Debian's signed-APT ca-certificates package `20250419+deb13u1` (package SHA-256 `c10585ac22c7f617a73d394eea29ba71d0356f350ae973b19043d1b017f80f54`). No environment proxy CA is bundled.

The OCI image Docker Library source is https://github.com/debuerreotype/docker-debian-artifacts and Debian source packages are available from https://deb.debian.org/debian/ and https://deb.debian.org/debian-security/. GPL terms of the app are in COPYING.

With Android API 37.0, build-tools 37.0.0 and JDK 21, run:

```sh
python3 tools/prepare-catalog-native.py app-play-debug.apk extracted-verified-libs
./gradlew -PdnPrebuiltNative="$(pwd)/extracted-verified-libs" :app:assemblePlayDebug :app:testPlayDebugUnitTest
```

The preview APK is debug-signed and is not production-ready.
