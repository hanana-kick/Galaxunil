package app.droidnative.display

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IndependentWindowBoundsTest {
    @Test fun maximizedGuestGetsMovableNativeTaskRespectingDisplayInsets() {
        val work = WindowArea(100, 60, 1540, 900)
        val base = WindowArea(1300, 700, 1540, 900)
        val a = IndependentWindowBounds.calculate(work, base, 4000, 3000, 40, 28, 11)
        assertEquals(1200, a.width)
        assertEquals(700, a.height)
        assertTrue(a.left >= work.left && a.top >= work.top && a.right <= work.right && a.bottom <= work.bottom)
    }

    @Test fun twoToplevelsFromOneApplicationHaveSeparateLaunchPositions() {
        val work = WindowArea(0, 40, 1920, 1040)
        val base = WindowArea(170, 120, 1000, 900)
        val one = IndependentWindowBounds.calculate(work, base, 800, 500, 32, 28, 8)
        val two = IndependentWindowBounds.calculate(work, base, 800, 500, 32, 28, 9)
        assertNotEquals(one, two)
        assertEquals(532, one.height)
        assertEquals(532, two.height)
    }

    @Test fun zeroFirstFrameStillGetsUsableBoundsOnAttachedDisplay() {
        val work = WindowArea(1920, 44, 3200, 764)
        val a = IndependentWindowBounds.calculate(work, null, 0, 0, 40, 28, 8)
        assertEquals(853, a.width)
        assertEquals(480, a.height)
        assertTrue(a.left >= work.left && a.top >= work.top && a.right <= work.right && a.bottom <= work.bottom)
    }
}
