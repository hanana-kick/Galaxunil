package app.droidnative

import app.droidnative.apps.AptRemoval
import org.junit.Assert.*
import org.junit.Test

class AptRemovalTest {
    private fun receipt(total: Int, vararg rows: String): String = listOf(
        "Reading package lists...", "Building dependency tree...",
        "0 upgraded, 0 newly installed, $total to remove and 1 not upgraded.", *rows,
    ).joinToString("\n")

    @Test fun acceptsExactlyTheSelectedPackage() {
        val plan = AptRemoval.parse("firefox-esr", receipt(1, "Remv firefox-esr [153.8.0]"))
        assertTrue(plan.allowed)
        assertEquals(listOf("firefox-esr"), plan.removals)
    }

    @Test fun listsButDisallowsReverseDependencies() {
        val plan = AptRemoval.parse("firefox-esr",
            receipt(2, "Remv app-extension [1.0]", "Remv firefox-esr [153.8.0]"))
        assertFalse(plan.allowed)
        assertEquals(listOf("app-extension", "firefox-esr"), plan.removals)
    }

    @Test fun rejectsMixedMissingAndDuplicateActions() {
        listOf(
            receipt(1, "Purg firefox-esr [153.8.0]"),
            receipt(2, "Remv firefox-esr [153.8.0]", "Inst replacement [1]"),
            receipt(1, "Remv another-package [1.0]"),
            receipt(2, "Remv firefox-esr [153.8.0]", "Remv firefox-esr [153.8.0]"),
            receipt(2, "Remv firefox-esr [153.8.0]"),
            receipt(1, "Remv firefox-esr:arm64 [153.8.0]"),
            "",
        ).forEach { assertTrue(runCatching { AptRemoval.parse("firefox-esr", it) }.isFailure) }
    }

    @Test fun protectsRuntimeAndRejectsUnexpectedStatus() {
        assertFalse(AptRemoval.eligible("mesa-utils"))
        assertFalse(AptRemoval.eligible("--purge"))
        assertFalse(AptRemoval.eligible("firefox-esr;id"))
        assertTrue(AptRemoval.unprotected("installed\tno\tno\toptional\n"))
        assertTrue(AptRemoval.unprotected("installed\t\t\textra\n"))
        assertFalse(AptRemoval.unprotected("installed\tyes\tno\toptional\n"))
        assertFalse(AptRemoval.unprotected("installed\tno\tyes\toptional\n"))
        assertFalse(AptRemoval.unprotected("installed\tno\tno\trequired\n"))
        assertFalse(AptRemoval.unprotected("config-files\tno\tno\toptional\n"))
        assertFalse(AptRemoval.unprotected("installed\tno\tno\toptional\ninstalled\tno\tno\textra\n"))
    }
}
