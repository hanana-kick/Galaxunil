package app.droidnative.display

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.os.Build
import android.view.WindowInsets
import android.widget.FrameLayout
import app.droidnative.env.EnvService
import app.droidnative.R
import app.droidnative.overlay.OverlayStore
import app.droidnative.overlay.OverlayView
import app.droidnative.apps.LinuxApps
import app.droidnative.apps.Rotation
import app.droidnative.apps.WindowSettings
import android.content.pm.ActivityInfo
import android.widget.ImageView
import kotlin.math.roundToInt

/**
 * Hosts one Wayland toplevel in its own Android task (document mode), so each
 * Linux app shows up as a separate entry in recents.
 *
 * Extras: [EXTRA_TOPLEVEL] (compositor id). The data URI keeps one task per
 * toplevel ("intoExisting").
 */
class WindowActivity : Activity(), SurfaceHolder.Callback, Compositor.Listener {

    private var toplevelId = 0
    private var compositor: Compositor? = null
    private var attached = false
    @Volatile private var clientClosed = false
    private lateinit var view: WindowView
    // Buffer pixels per view pixel (below 1 when a lower resolution is set)
    private var renderScale = 1f
    private lateinit var settings: WindowSettings
    private lateinit var input: ToplevelInput
    private var taskAppName: String? = null
    private var taskIcon: Bitmap? = null
    private var taskIdentityKey: String? = null
    private var diagnosticWidth = 0
    private var diagnosticHeight = 0
    private var diagnosticFocus: Boolean? = null
    // Moved into the desktop (desktop emulation switched on): the program stays
    @Volatile private var handedOver = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Volume keys set the media volume, which Linux audio plays at
        volumeControlStream = android.media.AudioManager.STREAM_MUSIC
        toplevelId = intent.getIntExtra(EXTRA_TOPLEVEL, 0)
        compositor = EnvService.compositor
        if (intent.data != documentUri(toplevelId)) {
            finishAndRemoveTask()
            return
        }
        val tl = compositor?.toplevels?.get(toplevelId)
        if (tl == null) {
            finishAndRemoveTask()
            return
        }
        compositor?.addListener(this)
        compositor?.hosted?.add(toplevelId)
        WindowLauncher.hosted(tl, this)
        logWindow("hosted")
        getSystemService(android.app.NotificationManager::class.java).cancel(1000 + toplevelId)
        // Predictive back (API 33+) bypasses key events
        if (Build.VERSION.SDK_INT >= 33)
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT) { moveTaskToBack(true) }

        settings = WindowSettings.forClient(this, tl.pid)
        updateTaskDescription(tl)
        loadTaskIdentity(tl)
        renderScale = settings.renderFactor
        if (!WindowLauncher.isDesktopWindow(this) && !isInMultiWindowMode) requestedOrientation = when (settings.rotation) {
            Rotation.AUTO -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            Rotation.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            Rotation.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
        view = WindowView(this)
        view.holder.addCallback(this)
        view.isFocusable = true
        view.isFocusableInTouchMode = true
        // Render at the configured resolution; Android scales to the view
        view.addOnLayoutChangeListener { _, l, t, r, b, _, _, _, _ ->
            val w = ((r - l) * renderScale).roundToInt().coerceAtLeast(1)
            val h = ((b - t) * renderScale).roundToInt().coerceAtLeast(1)
            if (renderScale != 1f) view.holder.setFixedSize(w, h)
            if (::input.isInitialized) input.touch.clampCursor()
        }
        val frame = FrameLayout(this)
        frame.addView(view)
        // Pointer of the touchpad mode (Android draws none for us)
        val cursor = ImageView(this).apply {
            setImageResource(R.drawable.ic_cursor)
            visibility = View.GONE
        }
        frame.addView(cursor, FrameLayout.LayoutParams(
            (20 * resources.displayMetrics.density).roundToInt(),
            (20 * resources.displayMetrics.density).roundToInt()))
        input = ToplevelInput(compositor!!, toplevelId, view, cursor, settings, { renderScale }) { showKeyboard(it) }
        input.touch.locked = compositor?.pointerLocked?.contains(toplevelId) == true
        // On-screen buttons chosen for this app, drawn over the surface
        settings.overlay?.let { OverlayStore(this).get(it) }?.let { layout ->
            val o = OverlayView(this, layout, compositor!!, toplevelId) { toggleKeyboard() }
            input.overlay = o
            frame.addView(o, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }
        // Android 15 draws apps edge to edge and no longer resizes the
        // window for the keyboard: keep the surface clear of both
        if (Build.VERSION.SDK_INT >= 35) {
            frame.setOnApplyWindowInsetsListener { v, insets ->
                val i = insets.getInsets(WindowInsets.Type.systemBars() or
                    WindowInsets.Type.displayCutout() or WindowInsets.Type.ime())
                v.setPadding(i.left, i.top, i.right, i.bottom)
                WindowInsets.CONSUMED
            }
        }
        setContentView(frame)
        view.requestFocus()
        if (compositor?.textInputToplevel == toplevelId && settings.autoKeyboard) showKeyboard(true)
    }

    // Overlay keyboard button: the user may have closed it in between
    private fun toggleKeyboard() {
        val shown = if (Build.VERSION.SDK_INT >= 30)
            window.decorView.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true
        else keyboardShown
        showKeyboard(!shown)
    }

    private var keyboardShown = false

    private fun showKeyboard(show: Boolean) {
        keyboardShown = show
        val imm = getSystemService(InputMethodManager::class.java)
        if (show) {
            view.requestFocus()
            imm.showSoftInput(view, 0)
        } else {
            imm.hideSoftInputFromWindow(view.windowToken, 0)
        }
    }

    override fun onStart() {
        super.onStart()
        if (::view.isInitialized) WindowLauncher.noteWindow(this)
    }

    override fun onStop() {
        WindowLauncher.forgetWindow(this)
        super.onStop()
    }

    override fun onDestroy() {
        compositor?.removeListener(this)
        val ownsTask = WindowLauncher.unhosted(toplevelId, this)
        if (ownsTask) compositor?.hosted?.remove(toplevelId)
        // Finishing without the app having closed: the task was removed from
        // recents (back only moves it to the background), so the app ends too
        if (ownsTask && isFinishing && !clientClosed && !handedOver && toplevelId != 0) {
            logWindow("host-close")
            compositor?.closeFromHost(toplevelId)
        }
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    private fun updateTaskDescription(tl: Compositor.Toplevel) {
        val label = tl.title.ifBlank { taskAppName ?: tl.appId.ifBlank { getString(R.string.window_unnamed) } }
        setTaskDescription(ActivityManager.TaskDescription(label, taskIcon))
    }

    private fun loadTaskIdentity(tl: Compositor.Toplevel) {
        // GTK can set its app_id after the first Wayland configure.
        val key = settings.appId ?: tl.appId.takeIf { it.isNotBlank() } ?: return
        if (taskIdentityKey == key) return
        taskIdentityKey = key
        val app = applicationContext
        TASK_ICONS.execute {
            val resolved = runCatching {
                val apps = LinuxApps(app)
                val entry = apps.systemDesktopEntry(key.removeSuffix(".desktop")) ?: apps.findLoose(key)
                val icon = entry?.let(apps::icon)?.let { bitmap ->
                    val factor = (96f / maxOf(bitmap.width, bitmap.height)).coerceAtMost(1f)
                    if (factor < 1f) Bitmap.createScaledBitmap(bitmap, (bitmap.width * factor).roundToInt().coerceAtLeast(1),
                        (bitmap.height * factor).roundToInt().coerceAtLeast(1), true) else bitmap
                }
                entry?.name to icon
            }.getOrNull() ?: return@execute
            runOnUiThread {
                if (isFinishing || isDestroyed || taskIdentityKey != key) return@runOnUiThread
                taskAppName = resolved.first
                taskIcon = resolved.second
                updateTaskDescription(tl)
            }
        }
    }

    private fun logWindow(event: String) {
        val displayId = window.decorView.display?.displayId ?: -1
        Log.i("galaxunil-windows",
            "event=$event task=$taskId toplevel=$toplevelId display=$displayId " +
                "nativeDesktop=${WindowLauncher.isDesktopWindow(this)} size=${diagnosticWidth}x$diagnosticHeight")
    }

    /* Surface lifecycle */

    override fun surfaceCreated(holder: SurfaceHolder) {}

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        val c = compositor ?: return
        if (diagnosticWidth != width || diagnosticHeight != height) {
            diagnosticWidth = width
            diagnosticHeight = height
            logWindow("surface")
        }
        if (!attached) {
            c.attach(toplevelId, holder.surface, width, height, this)
            attached = true
        } else {
            c.resize(toplevelId, width, height)
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        compositor?.detach(toplevelId, this)
        attached = false
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // The window the user works in decides how new windows open
        if (!::view.isInitialized || handedOver || clientClosed) return
        if (diagnosticFocus != hasFocus) {
            diagnosticFocus = hasFocus
            logWindow(if (hasFocus) "focus" else "blur")
        }
        if (hasFocus) WindowLauncher.noteWindow(this)
        // Pointer capture ends with focus; take it again when coming back
        if (hasFocus && compositor?.pointerLocked?.contains(toplevelId) == true) view.requestPointerCapture()
        compositor?.focus(toplevelId, hasFocus)
    }

    override fun onMultiWindowModeChanged(isInMultiWindowMode: Boolean, newConfig: android.content.res.Configuration) {
        super.onMultiWindowModeChanged(isInMultiWindowMode, newConfig)
        if (hasWindowFocus() && ::view.isInitialized) WindowLauncher.noteWindow(this)
    }

    /* Compositor events */

    override fun onToplevelChanged(toplevel: Compositor.Toplevel) {
        if (toplevel.id == toplevelId) runOnUiThread {
            if (!isFinishing && !isDestroyed && ::settings.isInitialized) {
                updateTaskDescription(toplevel)
                loadTaskIdentity(toplevel)
            }
        }
    }

    override fun onToplevelClosed(id: Int) {
        if (id != toplevelId) return
        clientClosed = true
        runOnUiThread { finishAndRemoveTask() }
    }

    override fun onDesktopEmulation(enabled: Boolean) {
        if (!enabled) return
        handedOver = true
        runOnUiThread { finishAndRemoveTask() }
    }

    override fun onPointerLock(id: Int, locked: Boolean) {
        if (id != toplevelId) return
        runOnUiThread { applyPointerLock(locked) }
    }

    // Touch gestures turn relative; a real mouse is captured by Android so
    // it delivers raw movement (onCapturedPointerEvent) instead of a position
    private fun applyPointerLock(locked: Boolean) {
        if (::input.isInitialized) input.touch.locked = locked
        if (locked && hasWindowFocus()) view.requestPointerCapture()
        else if (!locked) view.releasePointerCapture()
    }

    override fun onTextInput(id: Int, active: Boolean) {
        // Hiding always follows the app; showing only if wanted for this app
        if (id == toplevelId && (!active || settings.autoKeyboard)) runOnUiThread { showKeyboard(active) }
    }

    /** Surface that accepts an input method. */
    private inner class WindowView(context: Context) : SurfaceView(context) {
        override fun onCheckIsTextEditor() = true

        // Here, not in the activity: view coordinates are surface
        // coordinates, window coordinates include the status bar
        override fun onTouchEvent(event: MotionEvent) = input.onTouch(event)
        override fun onCapturedPointerEvent(event: MotionEvent) = input.onCapturedPointer(event)
        override fun onGenericMotionEvent(event: MotionEvent) =
            input.onGenericMotion(event) || super.onGenericMotionEvent(event)
        override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection =
            input.createInputConnection(outAttrs)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!::input.isInitialized) return super.dispatchKeyEvent(event)
        if (event.keyCode == KeyEvent.KEYCODE_BACK && event.device?.isVirtual != false &&
            !event.isFromSource(android.view.InputDevice.SOURCE_MOUSE)) {
            // System back: leave the app running, like Android's own apps
            if (event.action == KeyEvent.ACTION_UP) moveTaskToBack(true)
            return true
        }
        return input.key(event) || super.dispatchKeyEvent(event)
    }

    companion object {
        private val TASK_ICONS = java.util.concurrent.Executors.newFixedThreadPool(2)

        const val EXTRA_TOPLEVEL = "app.droidnative.extra.TOPLEVEL"

        private fun documentUri(id: Int) = Uri.parse("galaxunil://toplevel/${EnvService.compositor.windowSession}/$id")

        fun intent(context: Context, toplevelId: Int): Intent =
            Intent(context, WindowActivity::class.java)
                .setData(documentUri(toplevelId))
                .putExtra(EXTRA_TOPLEVEL, toplevelId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NEW_DOCUMENT)
    }
}
