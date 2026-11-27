package app.droidnative.diagnostics

import android.os.SystemClock
import android.util.Base64
import java.io.File
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
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/** Opt-in, in-memory session for events and app-scoped diagnostics. */
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
    enum class LogState { OFF, SENDING, LIMIT, FAILED }
    private val _logState = MutableStateFlow(LogState.OFF)
    val logState = _logState.asStateFlow()
    private class Session(var pendingPin: String?, val deadline: Long, val logDir: File) {
        val queue = Channel<Event>(capacity = 24)
        var job: Job? = null
        val connections = mutableSetOf<HttpsURLConnection>()
        var logcat: java.lang.Process? = null
        val logJobs = mutableListOf<Job>()
    }
    private const val SESSION_MS = 15 * 60 * 1000L
    // Fixed service origin only; never obtained from entered data.
    private const val ORIGIN = "https://galaxunil-live-ingress.bbggkkk.chatgpt.site"
    private val pinPattern = Regex("[0-9]{4}")
    private val tokenPattern = Regex("[0-9a-f]{64}")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(State.OFF)
    val state = _state.asStateFlow()
    private var current: Session? = null
    fun accepts(value: String) = pinPattern.matches(value.trim())

    @Synchronized fun start(input: String, logDir: File) {
        val pin = input.trim()
        require(accepts(pin))
        finishLocked(State.OFF)
        val session = Session(pin, SystemClock.elapsedRealtime() + SESSION_MS, logDir)
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
        session?.pendingPin = null
        session?.queue?.close()
        session?.job?.cancel()
        session?.logJobs?.forEach { it.cancel() }
        session?.logcat?.destroy()
        session?.connections?.toList()?.forEach { conn -> scope.launch { conn.disconnect() } }
        _logState.value = LogState.OFF
        _state.value = state
    }

    private suspend fun run(session: Session) {
        var outcome = State.OFF
        try {
            val pin = synchronized(this) { session.pendingPin.also { session.pendingPin = null } } ?: return
            // Never auto-retry redemption: the server may have consumed the PIN before a reply was lost.
            val pairing = try { request(session, "/v1/pair", """{"pin":"$pin"}""") }
                catch (_: IOException) { outcome = State.UNREACHABLE; return }
            if (pairing.status != 200) {
                outcome = if (pairing.status in 400..499) State.REJECTED else State.UNREACHABLE
                return
            }
            val token = try { JSONObject(pairing.body).optString("token") } catch (_: JSONException) { "" }
            if (!tokenPattern.matches(token)) { outcome = State.UNREACHABLE; return }
            var seq = 1
            var message = Event.START
            while (SystemClock.elapsedRealtime() < session.deadline && seq <= 512) {
                var status = 0
                for (i in 0..2) {
                    status = try { send(session, token, seq, message) } catch (_: IOException) { 0 }
                    if (status == 204 || status in 400..499 && status != 409) break
                    delay(1000L shl i)
                }
                when (status) {
                    204 -> synchronized(this) {
                        if (current === session) {
                            _state.value = State.LIVE
                            if (seq == 1) sendLogs(session, token)
                        }
                    }
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

    private fun sendLogs(session: Session, token: String) {
        val queue = Channel<DebugLogCapture.Chunk>(capacity = 64)
        _logState.value = LogState.SENDING
        val files = scope.launch { DebugLogCapture.files(session.logDir, queue) }
        val cat = scope.launch {
            DebugLogCapture.android(queue) { process ->
                synchronized(this@LiveDiagnostics) {
                    if (current !== session) false
                    else { session.logcat = process; true }
                }
            }
        }
        val uploader = scope.launch {
            var seq = 1
            var finalState = LogState.FAILED
            try {
                for (chunk in queue) {
                    if (SystemClock.elapsedRealtime() >= session.deadline) break
                    val data = Base64.encodeToString(chunk.bytes, Base64.NO_WRAP)
                    val body = """{"seq":$seq,"source":"${chunk.source}","data":"$data"}"""
                    var status = 0
                    for (attempt in 0..2) {
                        status = try { request(session, "/v1/logs", body, token).status }
                                 catch (_: IOException) { 0 }
                        if (status == 204 || status in 400..499) break
                        delay(500L shl attempt)
                    }
                    if (status != 204) {
                        if (status == 413) finalState = LogState.LIMIT
                        break
                    }
                    seq++
                }
            } finally {
                files.cancel()
                cat.cancel()
                synchronized(this@LiveDiagnostics) {
                    session.logcat?.destroy()
                    if (current === session) _logState.value = finalState
                }
                queue.cancel()
            }
        }
        session.logJobs.addAll(listOf(files, cat, uploader))
    }

    private data class Response(val status: Int, val body: String = "")

    private fun send(session: Session, token: String, seq: Int, event: Event): Int =
        request(session, "/v1/ingest", """{"seq":$seq,"event":"${event.wire}"}""", token).status

    private fun request(session: Session, path: String, payload: String, token: String? = null): Response {
        val conn = (URL(ORIGIN + path).openConnection() as HttpsURLConnection).apply {
            instanceFollowRedirects = false
            requestMethod = "POST"
            connectTimeout = 4000
            readTimeout = 4000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            if (token != null) setRequestProperty("Authorization", "Bearer $token")
        }
        val body = payload.toByteArray(Charsets.UTF_8)
        conn.setFixedLengthStreamingMode(body.size)
        synchronized(this) {
            if (current !== session) { conn.disconnect(); return Response(-1) }
            session.connections.add(conn)
        }
        try {
            conn.outputStream.use { it.write(body) }
            val status = conn.responseCode
            if (status != 200 || path != "/v1/pair") return Response(status)
            return conn.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                val out = CharArray(1025)
                var used = 0
                while (used < out.size) {
                    val n = reader.read(out, used, out.size - used)
                    if (n < 0) break
                    used += n
                }
                if (used > 1024) throw IOException("Oversized pairing reply")
                Response(status, String(out, 0, used))
            }
        } finally {
            conn.disconnect()
            synchronized(this) { session.connections.remove(conn) }
        }
    }
}
