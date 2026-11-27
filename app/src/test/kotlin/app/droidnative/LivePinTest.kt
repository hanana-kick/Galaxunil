package app.droidnative

import app.droidnative.diagnostics.LiveDiagnostics
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LivePinTest {
    @Test fun acceptsOnlyFourAsciiDigitsAndKeepsLeadingZeroes() {
        assertTrue(LiveDiagnostics.accepts("0027"))
        assertTrue(LiveDiagnostics.accepts("0000"))
        assertFalse(LiveDiagnostics.accepts("123"))
        assertFalse(LiveDiagnostics.accepts("12345"))
        assertFalse(LiveDiagnostics.accepts("１2３4"))
        assertFalse(LiveDiagnostics.accepts("123a"))
        assertFalse(LiveDiagnostics.accepts("f".repeat(64)))
    }
}
