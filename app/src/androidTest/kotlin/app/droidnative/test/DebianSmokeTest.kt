package app.droidnative.test

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.droidnative.MainActivity
import app.droidnative.env.EnvPaths
import app.droidnative.env.Proot
import app.droidnative.env.RootfsInstaller
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Offline smoke for a fresh, explicitly selected test emulator, not the upstream Void suite. */
@RunWith(AndroidJUnit4::class)
class DebianSmokeTest {
    @Test
    fun launchesAndRunsBundledDebianGuest() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("io.github.hananakick.galaxunil", ctx.packageName)
        val paths = EnvPaths(ctx)
        assertFalse("Refusing to replace an existing guest", paths.rootfs.exists() || paths.rootfsReady.exists())
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertTrue("Main Activity must have attached content", activity.window.decorView.isAttachedToWindow)
            }
        }

        val installer = RootfsInstaller(ctx, paths)
        installer.install()
        assertTrue("Bundled rootfs did not become ready", installer.isInstalled)
        val guest = Proot(paths)
        fun run(vararg args: String): String {
            val result = guest.run(args.toList(), root = true, timeoutMs = 60_000L)
            assertTrue("Guest command failed: ${args.first()}: ${result.output}", result.ok)
            return result.output
        }
        val os = run("/bin/cat", "/etc/os-release").lineSequence()
            .filter { "=" in it }.associate { line -> line.substringBefore("=") to line.substringAfter("=").trim('"') }
        assertEquals("debian", os["ID"])
        assertEquals("13", os["VERSION_ID"])
        assertTrue("Guest did not run apt-get", run("/usr/bin/apt-get", "--version").lineSequence()
            .any { it.startsWith("apt ") })
    }
}
