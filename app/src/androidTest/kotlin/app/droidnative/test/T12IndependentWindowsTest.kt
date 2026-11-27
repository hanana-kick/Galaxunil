package app.droidnative.test

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.droidnative.MainActivity
import app.droidnative.display.Compositor
import app.droidnative.display.WindowActivity
import app.droidnative.display.WindowLauncher
import app.droidnative.env.Prefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Run on a disposable Android Desktop Windowing/DeX device with the guest installed. */
@RunWith(AndroidJUnit4::class)
class T12IndependentWindowsTest {
    @Test fun newToplevelGetsOwnTaskOnSameNativeDesktop() = withDesktop { compositor, windows, processes, probe ->
        fun launch(title: String): Compositor.Toplevel {
            // Debian setup already includes Wayland/GTK dependencies but does
            // not ship the historical Void dn-wltest fixture.
            processes += runGuest(listOf("zenity", "--info", "--title=$title", "--text=$title"),
                mapOf("GDK_BACKEND" to "wayland"))
            val w = checkNotNull(windows.poll(30, TimeUnit.SECONDS)) { "Wayland toplevel did not appear" }
            awaitRendered(compositor, w, probe)
            return w
        }
        val first = launch("Galaxunil native A")
        val second = launch("Galaxunil native B")
        assertNativePair(first, second, probe)
        await("both surfaces attached") { compositor.isAttached(first.id) && compositor.isAttached(second.id) }
        // Optional Android shell assertions for the managed AOSP API-37 AVD.
        // Samsung/OEM verification can use the normal caption drag and click.
        if (InstrumentationRegistry.getArguments().getString("verifyResizeFocus") == "true") {
            assertIndependentResizeAndFocus(compositor, first, second, probe)
        }
        compositor.close(second.id)
        await("second task removed independently") { taskIds(second.id).isEmpty() }
        assertEquals(1, taskIds(first.id).size)
        TestEnv.report("native-task-pair", "two distinct native tasks rendered; second closed, first survived")
    }

    /** Same Firefox instance, no remote protocols or sandbox changes. The operator uses Firefox's menu. */
    @Test fun firefoxMoveTabToNewWindowGetsOwnTask() = withDesktop { compositor, windows, processes, probe ->
        val profile = "/tmp/galaxunil-native-window-" + java.util.UUID.randomUUID()
        val make = TestEnv.proot.run(listOf("mkdir", "-p", profile))
        assertTrue(make.output, make.ok)
        try {
            processes += runGuest(listOf("firefox-esr", "--no-remote", "--profile", profile,
                "about:blank", "about:robots"), mapOf("MOZ_ENABLE_WAYLAND" to "1", "NO_AT_BRIDGE" to "1"))
            val first = browserWindow(windows, "firefox", 60)
            awaitRendered(compositor, first, probe)
            TestEnv.report("firefox-detach", "READY: use Firefox's tab menu: Move Tab > Move to New Window")
            val second = browserWindow(windows, "firefox", 120)
            assertEquals("The same Firefox browser process must own the new window", first.pid, second.pid)
            assertTrue("The Firefox client must have a verified PID", first.pid > 0)
            awaitRendered(compositor, second, probe)
            assertNativePair(first, second, probe)
            compositor.close(second.id)
            await("detached Firefox task removed independently") { taskIds(second.id).isEmpty() }
            assertEquals(1, taskIds(first.id).size)
            TestEnv.report("firefox-detach", "PASS Firefox tabs separated into two tasks on the same native display")
        } finally {
            for (p in processes) {
                p.destroyForcibly()
                p.waitFor(5, TimeUnit.SECONDS)
            }
            TestEnv.proot.run(listOf("rm", "-rf", profile))
        }
    }

    /** Same Chromium profile/process, invoked through Android's keyboard path. */
    @Test fun chromiumNewWindowGetsOwnTask() = withDesktop { compositor, windows, processes, probe ->
        val profile = "/tmp/galaxunil-native-window-" + java.util.UUID.randomUUID()
        val make = TestEnv.proot.run(listOf("mkdir", "-p", profile))
        assertTrue(make.output, make.ok)
        try {
            processes += runGuest(listOf("chromium", "--ozone-platform=wayland",
                "--user-data-dir=$profile", "--no-first-run", "about:blank"))
            val first = browserWindow(windows, "chromium", 60)
            awaitRendered(compositor, first, probe)
            val inst = InstrumentationRegistry.getInstrumentation()
            val cmd = inst.uiAutomation.executeShellCommand("input keycombination 113 42")
            val input = android.os.ParcelFileDescriptor.AutoCloseInputStream(cmd).use { it.readBytes().decodeToString() }
            assertTrue("Ctrl+N injection: $input", input.isBlank())
            val second = browserWindow(windows, "chromium", 40)
            assertEquals("Same Chromium process", first.pid, second.pid)
            assertTrue("Verified browser PID", first.pid > 0)
            awaitRendered(compositor, second, probe)
            assertNativePair(first, second, probe)
            compositor.close(second.id)
            await("Chromium task removed independently") { taskIds(second.id).isEmpty() }
            assertEquals(1, taskIds(first.id).size)
            TestEnv.report("chromium-new-window", "PASS Ctrl+N produced a second rendered native task")
        } finally {
            for (p in processes) {
                p.destroyForcibly()
                p.waitFor(5, TimeUnit.SECONDS)
            }
            TestEnv.proot.run(listOf("rm", "-rf", profile))
        }
    }

    private fun browserWindow(windows: LinkedBlockingQueue<Compositor.Toplevel>, name: String, seconds: Int): Compositor.Toplevel {
        val deadline = SystemClock.elapsedRealtime() + seconds * 1000L
        while (SystemClock.elapsedRealtime() < deadline) {
            val w = windows.poll(500, TimeUnit.MILLISECONDS)
            if (w?.appId?.contains(name, ignoreCase = true) == true) return w
        }
        error("No real $name toplevel in ${seconds}s")
    }

    private fun runGuest(argv: List<String>, env: Map<String, String> = emptyMap()): Process =
        TestEnv.proot.start(argv, env).also { p ->
            p.outputStream.close()
            Thread {
                p.inputStream.use { input ->
                    val b = ByteArray(4096)
                    while (input.read(b) != -1) { }
                }
            }.apply { isDaemon = true; start() }
        }

    private fun withDesktop(run: (Compositor, LinkedBlockingQueue<Compositor.Toplevel>, MutableList<Process>, Probe) -> Unit) {
        TestEnv.ensureSetup()
        val compositor = ClientRunner.startCompositor()
        val windows = LinkedBlockingQueue<Compositor.Toplevel>()
        val listener = object : Compositor.Listener {
            override fun onToplevelNew(toplevel: Compositor.Toplevel) {
                windows.offer(toplevel)
                // Same event path as EnvService, including a duplicate launch event.
                repeat(2) { WindowLauncher.open(TestEnv.context, toplevel) }
            }
        }
        val processes = mutableListOf<Process>()
        val inst = InstrumentationRegistry.getInstrumentation()
        val app = TestEnv.context.applicationContext as Application
        val probe = Probe()
        val prefs = Prefs(TestEnv.context)
        val prior = prefs.desktopEmulation
        // Enabled fallback must not embed apps in native Android desktop mode.
        prefs.desktopEmulation = true
        inst.runOnMainSync { app.registerActivityLifecycleCallbacks(probe) }
        compositor.addListener(listener)
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity {
                    assertTrue("Run this test with MainActivity already in DeX/desktop freeform",
                        WindowLauncher.isDesktopWindow(it))
                }
                run(compositor, windows, processes, probe)
            }
        } finally {
            prefs.desktopEmulation = prior
            inst.runOnMainSync { app.unregisterActivityLifecycleCallbacks(probe) }
            compositor.removeListener(listener)
            for (p in processes) p.destroyForcibly()
        }
    }

    private class Probe : Application.ActivityLifecycleCallbacks {
        data class Presentation(val nativeDesktop: Boolean, val display: Int, val task: Int)
        val windows = ConcurrentHashMap<Int, Presentation>()
        val activities = ConcurrentHashMap<Int, WindowActivity>()
        override fun onActivityResumed(a: Activity) {
            if (a is WindowActivity) {
                val id = a.intent.getIntExtra(WindowActivity.EXTRA_TOPLEVEL, -1)
                activities[id] = a
                windows[id] = Presentation(WindowLauncher.isDesktopWindow(a),
                    a.window.decorView.display?.displayId ?: -1, a.taskId)
            }
        }
        override fun onActivityCreated(a: Activity, state: Bundle?) {}
        override fun onActivityStarted(a: Activity) {}
        override fun onActivityPaused(a: Activity) {}
        override fun onActivityStopped(a: Activity) {}
        override fun onActivitySaveInstanceState(a: Activity, state: Bundle) {}
        override fun onActivityDestroyed(a: Activity) {
            if (a is WindowActivity)
                activities.remove(a.intent.getIntExtra(WindowActivity.EXTRA_TOPLEVEL, -1), a)
        }
    }

    private fun awaitRendered(compositor: Compositor, w: Compositor.Toplevel, probe: Probe) =
        await("toplevel ${w.id} task and rendered surface") {
            taskIds(w.id).size == 1 && probe.windows[w.id]?.nativeDesktop == true &&
                compositor.stats(w.id)?.let { it.framesPresented > 0 && it.hasWindow } == true
        }

    private fun assertNativePair(a: Compositor.Toplevel, b: Compositor.Toplevel, probe: Probe) {
        assertNotEquals(a.id, b.id)
        assertNotEquals(taskIds(a.id).single(), taskIds(b.id).single())
        val aHost = checkNotNull(probe.windows[a.id])
        val bHost = checkNotNull(probe.windows[b.id])
        assertTrue(aHost.nativeDesktop && bHost.nativeDesktop && aHost.display >= 0)
        assertEquals(aHost.display, bHost.display)
        assertNotEquals(aHost.task, bHost.task)
    }

    private fun assertIndependentResizeAndFocus(compositor: Compositor, a: Compositor.Toplevel,
                                                b: Compositor.Toplevel, probe: Probe) {
        check(Build.VERSION.SDK_INT >= 30)
        val inst = InstrumentationRegistry.getInstrumentation()
        fun shell(command: String): String = android.os.ParcelFileDescriptor.AutoCloseInputStream(
            inst.uiAutomation.executeShellCommand(command)).use { it.readBytes().decodeToString() }
        fun bounds(id: Int, maximum: Boolean = false): Rect {
            lateinit var result: Rect
            inst.runOnMainSync {
                val wm = checkNotNull(probe.activities[id]).windowManager
                result = Rect((if (maximum) wm.maximumWindowMetrics else wm.currentWindowMetrics).bounds)
            }
            return result
        }
        fun focus(id: Int) {
            val receipt = shell("am task focus " + taskIds(id).single())
            assertTrue(receipt, !receipt.contains("Error", ignoreCase = true))
            await("Android window focus for toplevel $id") {
                var focused = false
                inst.runOnMainSync { focused = probe.activities[id]?.hasWindowFocus() == true }
                focused
            }
        }
        focus(a.id)
        focus(b.id)
        val oldA = bounds(a.id)
        val oldB = bounds(b.id)
        val max = bounds(b.id, maximum = true)
        val target = Rect(max.left + max.width() / 5, max.top + max.height() / 5,
            max.left + max.width() * 4 / 5, max.top + max.height() * 4 / 5)
        if (target == oldB) target.right += max.width() / 10
        val targetArgs = listOf(target.left, target.top, target.right, target.bottom).joinToString(" ")
        val frames = checkNotNull(compositor.stats(b.id)).framesPresented
        val resize = shell("am task resize " + taskIds(b.id).single() + " " + targetArgs)
        assertTrue(resize, !resize.contains("Error", ignoreCase = true))
        await("second native bounds changed and repainted") {
            bounds(b.id) != oldB && (compositor.stats(b.id)?.framesPresented ?: 0) > frames
        }
        assertEquals("Resizing the second Android task did not resize the first", oldA, bounds(a.id))
        TestEnv.report("native-task-pair", "both focus transitions passed; B resized/repainted alone")
    }

    private fun taskIds(id: Int): List<Int> {
        val context = TestEnv.context
        val scheme = WindowActivity.intent(context, id).data
        val am = context.getSystemService(ActivityManager::class.java)
        return am.appTasks.mapNotNull { task ->
            val info = task.taskInfo ?: return@mapNotNull null
            if (info.baseIntent.data == scheme) info.taskId else null
        }
    }

    private fun await(label: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 20_000
        while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(150)
        assertTrue(label, condition())
    }
}
