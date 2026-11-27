package app.droidnative.diagnostics

import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/** Opt-in, in-memory session. Only fixed application events leave this process. */
internal object LiveDiagnostics {
    enum class Event(val wire: String) {
        START("session-start"), HEARTBEAT("heartbeat"),
        SETUP_START("setup-start"), SETUP_INDEX("setup-index"), SETUP_PACKAGES("setup-packages"),
        SETUP_COMPLETE("setup-complete"), SETUP_DNS_FAILED("setup-dns-failed"), SETUP_FAILED("setup-failed"),
        ENVIRONMENT_START("environment-start"), ENVIRONMENT_STOP("environment-stop"),
        CATALOG_START("catalog-start"), CATALOG_COMPLETE("catalog-complete"), CATALOG_FAILED("catalog-failed"),
        APP_LAUNCH("app-launch"), APP_WINDOW("app-window"), APP_FAILED("app-failed"),
        APP_WAYLAND("app-wayland"), APP_GRAPHICS("app-graphics"), APP_SANDBOX("app-sandbox"),
        APP_DEPENDENCY("app-dependency"),
    }
    enum class State { OFF, CONNECTING, LIVE, REJECTED, UNREACHABLE }
    private class Session(val code: String, val deadline: Long) {
        val queue = Channel<Event>(capacity = 24)
        var job: Job? = null
        var connection: HttpsURLConnection? = null
    }
    private const val SESSION_MS = 15 * 60 * 1000L
    // Fixed service origin only; never obtained from entered data.
    private const val ENDPOINT = "https://galaxunil-live-ingress.bbggkkk.chatgpt.site/v1/ingest"
    private val codePattern = Regex("[0-9a-f]{64}")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(State.OFF)
    val state = _state.asStateFlow()
    private var current: Session? = null
    fun accepts(value: String) = codePattern.matches(value.trim())

    @Synchronized fun start(input: String) {
        val token = input.trim()
        require(accepts(token))
        finishLocked(State.OFF)
        val session = Session(token, SystemClock.elapsedRealtime() + SESSION_MS)
        current = session
        _state.value = State.CONNECTING
        session.job = scope.launch { run(session) }
    }

    @Synchronized fun stop() { finishLocked(State.OFF) }

    @Synchronized fun event(event: Event) {
        current?.takeIf { SystemClock.elapsedRealtime() < it.deadline }?.queue?.trySend(event)
    }

    private fun finishLocked(state: State) {
        val session = current
        current = null
        session?.queue?.close()
        session?.job?.cancel()
        session?.connection?.let { conn -> scope.launch { conn.disconnect() } }
        _state.value = state
    }

    private suspend fun run(session: Session) {
        var outcome = State.OFF
        try {
            var seq = 1
            var message = Event.START
            while (SystemClock.elapsedRealtime() < session.deadline && seq <= 512) {
                var status = 0
                for (i in 0..2) {
                    status = try { send(session, seq, message) } catch (_: IOException) { 0 }
                    if (status == 204 || status in 400..499 && status != 409) break
                    delay(1000L shl i)
                }
                when (status) {
                    204 -> synchronized(this) { if (current === session) _state.value = State.LIVE }
                    in 400..499 -> { outcome = State.REJECTED; break }
                    else -> { outcome = State.UNREACHABLE; break }
                }
                seq++
                val left = session.deadline - SystemClock.elapsedRealtime()
                if (left <= 0) break
                message = withTimeoutOrNull(minOf(12_000L, left)) { session.queue.receive() } ?: Event.HEARTBEAT
            }
        } catch (_: CancellationException) {
            // Off has already disconnected the active request.
        } finally {
            synchronized(this) { if (current === session) finishLocked(outcome) }
        }
    }

    private fun send(session: Session, seq: Int, event: Event): Int {
        val conn = (URL(ENDPOINT).openConnection() as HttpsURLConnection).apply {
            instanceFollowRedirects = false
            requestMethod = "POST"
            connectTimeout = 4000
            readTimeout = 4000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer ${session.code}")
        }
        val body = """{"seq":$seq,"event":"${event.wire}"}""".toByteArray(Charsets.UTF_8)
        conn.setFixedLengthStreamingMode(body.size)
        synchronized(this) {
            if (current !== session) { conn.disconnect(); return -1 }
            session.connection = conn
        }
        try {
            conn.outputStream.use { it.write(body) }
            return conn.responseCode
        } finally {
            conn.disconnect()
            synchronized(this) { if (session.connection === conn) session.connection = null }
        }
    }
}
