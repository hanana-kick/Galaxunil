package app.droidnative

import app.droidnative.apps.CuratedCatalog
import app.droidnative.apps.CuratedCatalog.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CuratedCatalogTest {
    private val firefox = CuratedCatalog.apps.single { it.packageName == "firefox" }
    @Test fun requiresPackageReceiptAndDesktopEntryBeforeOfferingOpen() {
        assertEquals(State.CHECKING, CuratedCatalog.state(null, false, emptySet(), firefox))
        assertEquals(State.CHECK_FAILED, CuratedCatalog.state(null, true, emptySet(), firefox))
        assertEquals(State.NOT_INSTALLED, CuratedCatalog.state(emptySet(), false, setOf("firefox"), firefox))
        assertEquals(State.INSTALLED_WITHOUT_LAUNCHER, CuratedCatalog.state(setOf("firefox"), false, emptySet(), firefox))
        assertEquals(State.OPEN, CuratedCatalog.state(setOf("firefox"), false, setOf("firefox"), firefox))
    }

    @Test fun readsOnlyFullyInstalledPackagesFromXbps() {
        val receipt = """
            ii firefox-161.0_1 Firefox
            uu firefox-esr-140.0_2 Unpacked
            hr gimp-3.2.8_1 Half removed
            ii another-app-1.0_5 Multi word description
            ?? inkscape-1.0_1 Invalid state
        """.trimIndent()
        assertEquals(setOf("firefox", "another-app"), CuratedCatalog.installedNames(receipt))
    }

    @Test fun matchesVisiblePackageOwnedAliasOnly() {
        val correct = "/usr/share/applications/firefox.desktop"
        val renamed = "/usr/share/applications/org.mozilla.firefox.desktop"
        assertEquals("firefox", CuratedCatalog.matchDesktopId(firefox, setOf(correct), setOf("firefox")))
        assertEquals("org.mozilla.firefox",
            CuratedCatalog.matchDesktopId(firefox, setOf(correct, renamed), setOf("org.mozilla.firefox")))
        assertEquals(null, CuratedCatalog.matchDesktopId(firefox, setOf("/tmp/firefox.desktop"), setOf("firefox")))
        assertEquals(null, CuratedCatalog.matchDesktopId(firefox,
            setOf("/usr/share/applications/random.desktop"), setOf("random")))
    }

    @Test fun onlyAllowsInAppFixedPackageNames() {
        assertTrue(CuratedCatalog.accepts("firefox"))
        assertFalse(CuratedCatalog.accepts("--repository"))
        assertFalse(CuratedCatalog.accepts("firefox;id"))
        assertFalse(CuratedCatalog.accepts("https://example.com/firefox.xbps"))
    }
}
