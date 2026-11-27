# Linux App Catalog

DroidNative `beta-0.1` commit `fa591010d6e33adbf5a79178b874fd4abdc58931`. The feature appears at the top of the Install tab; installation and launch buttons become available after first-run Linux setup. It uses the existing XBPS engine and native launch activity. It does not require a second Android app.

- Initial catalog: Firefox, GIMP, Inkscape, and Audacity. XBPS package names are fixed in source, not taken from links, search boxes, or external catalogs.
- Installation uses the environment's normal **signed** XBPS repositories. The curated path does not download rolling preset scripts for these four apps. The existing generic managers remain available separately.
- Open requires an XBPS receipt marked fully installed, a recognized desktop alias in that package's XBPS file list, and a resolvable desktop entry. Apps that never emit a window or exit early retain DroidNative's existing launch diagnostics.
- App settings uses the original per-app settings. Graphics setup displayed is not a claim of working acceleration. Default leaves the existing guest selection unchanged. VirGL and llvmpipe are existing choices; a software or translated rendering environment does not confirm real-device GPU use.

## Rebuild

Requires JDK 17+, Gradle wrapper 9.8.1, Android API 37.0, build-tools 37.0.0, and (when rebuilding native code) NDK 29.0.14206865/CMake 4.1.2. Follow the upstream README/native-host build instructions for additional native runtime components.

```sh
./gradlew :app:assemblePlayDebug :app:testPlayDebugUnitTest
```

For a side-by-side QA build use `-PdnCatalogTestId=true` (Android package `app.droidnative.catalog`, separate app storage). It cannot replace the upstream installation or read its private Linux data. A QA APK is debug-signed and is not intended as a production update.

For an unchanged native-runtime reuse build, supply `-PdnPrebuiltNative=/absolute/path/to/extracted-verified-libs` with ABI subdirectories. It is essential to verify the source release APK and not inject third-party libraries. Official APK:
https://github.com/DroidNative/DroidNativeApp/releases/download/beta-0.1/app-play-debug.apk
SHA-256: `37586ce708176e9a950346a0834e6d29ba373ba81c3b369b35e8394b3c414d72`.
Use `python3 tools/prepare-catalog-native.py app-play-debug.apk extracted-verified-libs` to verify and extract just the runtime libraries.
The `libandroidx.graphics.path.so` library is supplied by Gradle's Compose dependencies; exclude it from extracted libraries. Android native integration remains unchanged.

Source is distributed under upstream terms in COPYING. See upstream repository and native-host build scripts for accompanying upstream dependencies and sources.
