package app.droidnative

import app.droidnative.diagnostics.DiagnosticReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticReportTest {
    @Test fun matchesPrivateIntakeFormat() {
        val report = DiagnosticReport("SM-X736B", "16", 36, "0.1.0", 4, false,
            DiagnosticReport.Setup.FAILED, DiagnosticReport.Symptom.DNS)
        assertEquals(
            """{"model":"SM-X736B","android":"16","sdk":"36","appVersion":"0.1.0","versionCode":"4","build":"physical","runtime":"setup-failed","symptom":"apt-dns"}""",
            report.json(),
        )
    }
    @Test fun onlyFixedSymptomLeavesTheDevice() {
        val secret = "private.example/path"
        val report = DiagnosticReport("Galaxy\"\nTab", "", 36, "\"bad", -1, true,
            DiagnosticReport.Setup.UNKNOWN,
            DiagnosticReport.classify("Temporary failure resolving '$secret'"))
        val json = report.json()
        assertFalse(json.contains(secret))
        assertTrue(json.contains("\"symptom\":\"apt-dns\""))
        assertTrue(json.contains("\"model\":\"GalaxyTab\""))
        assertTrue(json.contains("\"versionCode\":\"unknown\""))
    }
    @Test fun emptyAndOutOfWindowLogsAreNotReportedAsSpecificFailures() {
        assertEquals(DiagnosticReport.Symptom.NOT_TESTED, DiagnosticReport.classify(""))
        assertEquals(DiagnosticReport.Symptom.UNKNOWN,
            DiagnosticReport.classify("EGL_NOT_INITIALIZED" + "a".repeat(32_769)))
    }
}
