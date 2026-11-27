package app.droidnative.diagnostics

import android.os.Process
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest

/** Only the app UID's Android logs and this app's dedicated guest output directory. */
internal object DebugLogCapture {
    const val CHUNK_BYTES = 12_288
    data class Chunk(val source: String, val bytes: ByteArray)
    private data class Cursor(var offset: Long, var prefix: ByteArray, var revision: Int)
    private val validName = Regex("[a-zA-Z0-9_.-]{1,100}\\.log")

    suspend fun files(dir: File, out: SendChannel<Chunk>) {
        val canonical = dir.canonicalFile
        val known = HashMap<String, Cursor>()
        while (currentCoroutineContext().isActive) {
            val listed = dir.listFiles().orEmpty().sortedWith(compareBy({ it.name != "setup.log" }, { it.name }))
            for (file in listed) {
                if (!file.name.endsWith(".log")) continue
                try {
                    if (!file.isFile || file.canonicalFile != File(canonical, file.name)) continue
                    RandomAccessFile(file, "r").use { reader ->
                        val length = reader.length()
                        val state = known.getOrPut(file.name) { Cursor(0L, byteArrayOf(), 0) }
                        val oldPrefix = ByteArray(minOf(length, state.prefix.size.toLong()).toInt())
                        reader.readFully(oldPrefix)
                        if (length < state.offset || !oldPrefix.contentEquals(state.prefix)) {
                            state.offset = 0
                            state.prefix = byteArrayOf()
                            state.revision++
                        }
                        if (state.prefix.size < 128 && length > state.prefix.size) {
                            reader.seek(0)
                            state.prefix = ByteArray(minOf(length, 128).toInt()).also { reader.readFully(it) }
                        }
                        reader.seek(state.offset)
                        val source = if (validName.matches(file.name)) file.name else
                            "guest-" + MessageDigest.getInstance("SHA-256").digest(file.name.toByteArray())
                                .take(12).joinToString("") { "%02x".format(it.toInt() and 255) } + ".log"
                        val label = if (state.revision == 0) source else "$source#${state.revision}"
                        val buf = ByteArray(CHUNK_BYTES)
                        var remaining = length - state.offset
                        while (remaining > 0 && currentCoroutineContext().isActive) {
                            val n = reader.read(buf, 0, minOf(remaining, buf.size.toLong()).toInt())
                            if (n < 0) break
                            out.send(Chunk(label, buf.copyOf(n)))
                            state.offset += n
                            remaining -= n
                        }
                    }
                } catch (_: IOException) {
                    // File rotated or is not yet readable; try again without advancing its cursor.
                }
            }
            delay(500)
        }
    }

    suspend fun android(out: SendChannel<Chunk>, attach: (java.lang.Process?) -> Boolean) {
        val process = try {
            ProcessBuilder("/system/bin/logcat", "-b", "all",
                "--uid=${Process.myUid()}", "-v", "threadtime").start()
        } catch (_: IOException) { return }
        try {
            if (!attach(process)) return
            process.inputStream.use { input ->
                val buffer = ByteArray(CHUNK_BYTES)
                // Coalesce new messages without waiting for a full buffer if the app is idle.
                while (currentCoroutineContext().isActive) {
                    if (input.available() == 0 && !process.isAlive) break
                    if (input.available() == 0) { delay(250); continue }
                    delay(80)
                    val n = input.read(buffer, 0, minOf(buffer.size, input.available().coerceAtLeast(1)))
                    if (n == -1) break
                    out.send(Chunk("android-uid.log", buffer.copyOf(n)))
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: IOException) {
            // Do not substitute unrestricted Android logs if UID-scoped logcat is unavailable.
        } finally {
            process.destroy()
            attach(null)
        }
    }
}
