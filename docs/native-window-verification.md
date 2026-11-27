# Android/DeX app windows

A Linux xdg_toplevel and its Android document task use `galaxunil://toplevel/<compositor session>/<toplevel id>`. New windows wait for an app surface, not a new package or process. Android Desktop Windowing/DeX controls the native caption and resizing. The emulated, single-task desktop is a fallback. Background requests use the existing window notification; xdg_popup surfaces are still painted within their parent window.

## Reproducible checks

Use a disposable Android Desktop Windowing/DeX device with both windows on one display and a verified Debian setup. Install the Debian ARM64/x86_64 package `zenity` in that guest for the generic window test.

```sh
./gradlew :app:testPlayDebugUnitTest :app:assemblePlayDebug :app:assemblePlayDebugAndroidTest
adb install -r app/build/outputs/apk/play/debug/app-play-debug.apk
adb install -r app/build/outputs/apk/androidTest/play/debug/app-play-debug-androidTest.apk
adb shell am instrument -w -e class 'app.droidnative.test.T12IndependentWindowsTest#newToplevelGetsOwnTaskOnSameNativeDesktop' io.github.hananakick.galaxunil.test/androidx.test.runner.AndroidJUnitRunner
```

This checks two rendered Wayland toplevels open two native freeform activities on the same display even with software fallback enabled. Double requests do not produce duplicates, and closing the second guest window leaves the first task alive.

Install the official Debian package `firefox-esr` or `chromium` in the test guest first, then run the respective test:

```sh
adb shell am instrument -w -e class 'app.droidnative.test.T12IndependentWindowsTest#firefoxMoveTabToNewWindowGetsOwnTask' io.github.hananakick.galaxunil.test/androidx.test.runner.AndroidJUnitRunner
adb shell am instrument -w -e class 'app.droidnative.test.T12IndependentWindowsTest#chromiumNewWindowGetsOwnTask' io.github.hananakick.galaxunil.test/androidx.test.runner.AndroidJUnitRunner
```

For Firefox, after `firefox-detach READY` in `adb logcat -s dn-test`, right-click Firefox's second tab and choose **Move Tab → Move to New Window**. The Chromium check issues Ctrl+N through Android input. Each test requires two windows from the same browser process to render in distinct Android freeform tasks on the same display, and closes the second without closing the first. Both browsers use disposable profiles, and no sandbox flags are disabled. Chromium is not a Google Chrome test. Resize each Android window and check independent repaint/focus and app popup menus in an interactive smoke test.

Mouse drag-out and drag-back from the tab strip are distinct from the menu test. The pinned Wayland compositor's `wl_data_device.start_drag` cancels immediately; it does not create drag offers. Native task creation alone does not implement this gesture. It requires a compositor drag session and a rebuilt native runtime rather than relaxing OS/browser sandbox policy.

### Samsung DeX smoke test, without additional APKs per app

Enable Samsung DeX itself and open the Android launcher shortcut made in Galaxunil. The shortcut is optional and remains owned by the Galaxunil Android package. Do not launch Galaxunil's fallback single-window desktop for this test. Open a second top-level window from the same Linux app. Check both are visible separately on the same display, can be focused and resized independently, and that closing the second native window leaves the first alive. App menus must remain inside the owning window; test Firefox tab drag separately from the menu new-window operation.

With opt-in diagnostics enabled, app-only logcat records `galaxunil-windows` events with task ID, toplevel ID, display ID and buffer size. It does not record titles, URLs, or input. Two distinct task IDs on the same display plus affected-window surface/focus/close events corroborate inspection on the device; logs alone don't prove rendering.
