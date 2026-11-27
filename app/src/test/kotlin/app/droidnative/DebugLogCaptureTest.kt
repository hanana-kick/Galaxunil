package app.droidnative

import app.droidnative.diagnostics.DebugLogCapture
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files

class DebugLogCaptureTest {
    @Test fun backfillsExistingSetupLogThenAppendsAndDetectsReplacement() = runBlocking {
        val dir = Files.createTempDirectory("app-debug-log-").toFile()
        val file = dir.resolve("setup.log")
        val input = ByteArray(DebugLogCapture.CHUNK_BYTES + 7) { (it % 255).toByte() }
        file.writeBytes(input)
        val unreadable = dir.resolve("ignore.txt")
        unreadable.writeText("not a log")
        val external = Files.createTempFile("unrelated-data-", ".log")
        Files.createSymbolicLink(dir.resolve("linked.log").toPath(), external)
        val out = Channel<DebugLogCapture.Chunk>(8)
        val job = launch { DebugLogCapture.files(dir, out) }
        try {
            val first = withTimeout(4000) { out.receive() }
            val second = withTimeout(4000) { out.receive() }
            assertEquals("setup.log", first.source)
            assertEquals("setup.log", second.source)
            assertArrayEquals(input, first.bytes + second.bytes)
            file.appendText("E: 증분")
            val extra = withTimeout(4000) { out.receive() }
            assertArrayEquals("E: 증분".toByteArray(), extra.bytes)
            file.writeText("Err: 새 시도")
            val rotated = withTimeout(4000) { out.receive() }
            assertEquals("setup.log#1", rotated.source)
            assertArrayEquals("Err: 새 시도".toByteArray(), rotated.bytes)
        } finally {
            job.cancelAndJoin()
            dir.deleteRecursively()
            Files.delete(external)
        }
    }
}
