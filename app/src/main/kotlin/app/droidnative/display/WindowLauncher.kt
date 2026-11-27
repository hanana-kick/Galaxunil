package app.droidnative.display

import android.app.Activity
import android.app.ActivityOptions
import android.content.Context
import android.content.res.Configuration
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.WindowInsets
import app.droidnative.apps.WindowSettings
import app.droidnative.display.desktop.DesktopMode
import app.droidnative.display.desktop.DesktopState
import app.droidnative.env.EnvService
import kotlin.math.roundToInt

/**
 * One Android document task per Wayland toplevel. Choose the current *visible*
 * Android activity on the main thread, so an already-rendering Firefox window
 * can launch the new toplevel created by detaching a tab. A service alone does
 * not launch UI: EnvService offers a user-initiated notification instead.
 */
object WindowLauncher : Compositor.Listener {
    @Volatile var desktopMode = false
        private set

    private val handler = Handler(Looper.getMainLooper())
    private val visible = LinkedHashSet<Activity>()
    private val hosts = HashMap<Int, Host>()
    private val waiting = HashMap<Int, Request>()
    private val starting = HashMap<Int, Start>()
    private var listening = false

    private data class Host(val tl: Compositor.Toplevel, val activity: WindowActivity)
    private data class Start(val tl: Compositor.Toplevel, val deadline: Long)

    private class Request(val tl: Compositor.Toplevel) {
        var preferred: Activity? = null
        var reopen = false
        val callbacks = mutableListOf<() -> Unit>()
    }

    fun isDesktopWindow(activity: Activity): Boolean {
        val cfg = activity.resources.configuration
        if (samsungDesktop(cfg)) return true
        if (!activity.isInMultiWindowMode) return false
        // A native caption bar is public. The configuration text is a fallback
        // for builds whose caption insets are not dispatched to the app.
        if (Build.VERSION.SDK_INT >= 30 &&
            (activity.window.decorView.rootWindowInsets?.getInsetsIgnoringVisibility(
                WindowInsets.Type.captionBar())?.top ?: 0) > 0) return true
        return Regex("""mWindowingMode\s*=\s*freeform\b""").containsMatchIn(cfg.toString())
    }

    private fun samsungDesktop(cfg: Configuration): Boolean = runCatching {
        val c = Configuration::class.java
        c.getField("semDesktopModeEnabled").getInt(cfg) ==
            c.getField("SEM_DESKTOP_MODE_ENABLED").getInt(null)
    }.getOrDefault(false)

    /** Called on start, focus or resize, and paired with [forgetWindow] on stop. */
    fun noteWindow(activity: Activity) {
        visible.remove(activity)
        visible.add(activity)
        if (!activity.hasWindowFocus()) return
        val nativeDesktop = isDesktopWindow(activity)
        desktopMode = nativeDesktop
        EnvService.compositor.serverDecorations = nativeDesktop || DesktopMode.shouldEmulate(activity)
        if (nativeDesktop && DesktopState.host != null) DesktopMode.leaveEmulation(activity)
    }

    fun forgetWindow(activity: Activity) {
        visible.remove(activity)
    }

    fun hosted(tl: Compositor.Toplevel, activity: WindowActivity) {
        hosts[tl.id] = Host(tl, activity)
        starting.remove(tl.id)
    }

    fun unhosted(id: Int, activity: WindowActivity): Boolean {
        val host = hosts[id]?.takeIf { it.activity === activity } ?: return false
        hosts.remove(id)
        return EnvService.compositor.toplevels[id] === host.tl
    }

    fun hasNativeHost(id: Int): Boolean =
        hosts[id]?.let { it.tl === EnvService.compositor.toplevels[id] } == true

    private fun isNativeWindowPending(tl: Compositor.Toplevel): Boolean =
        starting[tl.id]?.let { it.tl === tl && it.deadline > SystemClock.elapsedRealtime() } == true

    private fun usable(activity: Activity): Boolean =
        activity in visible && !activity.isFinishing && !activity.isDestroyed &&
            activity.window.decorView.isAttachedToWindow && activity.window.decorView.isShown

    private fun source(preferred: Activity?): Activity? {
        val candidates = visible.filter(::usable)
        return preferred?.takeIf { usable(it) && it.hasWindowFocus() }
            ?: candidates.lastOrNull { it.hasWindowFocus() }
            ?: preferred?.takeIf(::usable)
            ?: candidates.lastOrNull()
    }

    /** Coalesce service+launcher events for the same toplevel; never dedupe by app/PID. */
    fun open(context: Context, tl: Compositor.Toplevel, reopen: Boolean = false, then: () -> Unit = {}) {
        handler.post {
            if (EnvService.compositor.toplevels[tl.id] !== tl) {
                then()
                return@post
            }
            if (!listening) {
                EnvService.compositor.addListener(this)
                listening = true
            }
            val queued = waiting[tl.id]
            val req = queued?.takeIf { it.tl === tl } ?: Request(tl).also { waiting[tl.id] = it }
            if (context is Activity) req.preferred = context
            req.reopen = req.reopen || reopen
            req.callbacks += then
            if (queued === req) return@post
            queued?.callbacks?.forEach { it() }
            if (tl.width > 0) handler.post { release(tl.id, req) }
            else handler.postDelayed({ release(tl.id, req) }, SIZE_WAIT_MS)
        }
    }

    override fun onToplevelSize(toplevel: Compositor.Toplevel) {
        handler.post {
            waiting[toplevel.id]?.takeIf { it.tl === toplevel }?.let { release(toplevel.id, it) }
        }
    }

    override fun onToplevelClosed(id: Int) {
        handler.post {
            starting.remove(id)
            waiting.remove(id)?.callbacks?.forEach { it() }
        }
    }

    private fun release(id: Int, req: Request) {
        if (!waiting.remove(id, req)) return
        try {
            if (EnvService.compositor.toplevels[id] !== req.tl) return
            val host = source(req.preferred) ?: return
            val nativeDesktop = isDesktopWindow(host)
            if (DesktopMode.shouldEmulate(host, nativeDesktop)) {
                // Already mapped popups are painted with their parent by the
                // native compositor, so only toplevels enter this path.
                DesktopState.host?.takeIf { !it.isFinishing }?.addWindow(req.tl)
                if (host !== DesktopState.host || req.reopen) DesktopMode.open(host)
            } else if (req.reopen || !hasNativeHost(id) && !isNativeWindowPending(req.tl)) {
                start(host, req.tl, nativeDesktop)
            }
        } finally {
            req.callbacks.forEach { it() }
        }
    }

    /** Move already-open emulated windows without waiting for a second first frame. */
    fun handoverFromDesktop(activity: Activity) {
        if (!usable(activity) || !isDesktopWindow(activity)) return
        EnvService.compositor.toplevels.values.sortedBy { it.id }.forEach { tl ->
            if (!hasNativeHost(tl.id) && !isNativeWindowPending(tl)) start(activity, tl, true)
        }
    }

    private fun start(source: Activity, tl: Compositor.Toplevel, nativeDesktop: Boolean) {
        try {
            starting[tl.id] = Start(tl, SystemClock.elapsedRealtime() + START_TIMEOUT_MS)
            val options = ActivityOptions.makeBasic()
            if (Build.VERSION.SDK_INT >= 26) {
                val displayId = source.window.decorView.display?.displayId
                if (displayId != null) options.setLaunchDisplayId(displayId)
            }
            if (nativeDesktop) launchBounds(source, tl)?.let { options.setLaunchBounds(it) }
            // Do not force a private windowing-mode setting. DeX and Android
            // Desktop Windowing own the task chrome, position and sizing.
            source.startActivity(WindowActivity.intent(source, tl.id), options.toBundle())
        } catch (e: Exception) {
            starting.remove(tl.id)
            Log.w(TAG, "cannot open window for ${tl.id}", e)
        }
    }

    private fun launchBounds(source: Activity, tl: Compositor.Toplevel): Rect? {
        val wm = source.windowManager
        val density = source.resources.displayMetrics.density
        val (display, base) = if (Build.VERSION.SDK_INT >= 30) {
            val max = wm.maximumWindowMetrics
            val insets = max.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            val work = Rect(max.bounds)
            work.inset(insets.left, insets.top, insets.right, insets.bottom)
            work to wm.currentWindowMetrics.bounds
        } else {
            @Suppress("DEPRECATION")
            val metrics = source.resources.displayMetrics
            Rect(0, 0, metrics.widthPixels, metrics.heightPixels) to null
        }
        if (display.isEmpty) return null
        val caption = if (Build.VERSION.SDK_INT >= 30)
            source.window.decorView.rootWindowInsets?.getInsetsIgnoringVisibility(WindowInsets.Type.captionBar())?.top
        else null
        val factor = WindowSettings.forClient(source, tl.pid).renderFactor.takeIf { it > 0f } ?: 1f
        val out = IndependentWindowBounds.calculate(
            WindowArea(display.left, display.top, display.right, display.bottom),
            base?.let { WindowArea(it.left, it.top, it.right, it.bottom) },
            (tl.width / factor).roundToInt(), (tl.height / factor).roundToInt(),
            caption?.takeIf { it > 0 } ?: (DEFAULT_CAPTION_DP * density).roundToInt(),
            (CASCADE_DP * density).roundToInt(), tl.id
        )
        return Rect(out.left, out.top, out.right, out.bottom)
    }

    private const val TAG = "dn-window"
    private const val SIZE_WAIT_MS = 400L
    private const val START_TIMEOUT_MS = 1500L
    private const val DEFAULT_CAPTION_DP = 40
    private const val CASCADE_DP = 28
}
