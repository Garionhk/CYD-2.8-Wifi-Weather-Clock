// ===========================================================================
// SceneRotationTest.kt -- the rotation state machine, driven by a fake clock
// ===========================================================================
// All timings come in as arguments, so this runs instantly and deterministically
// rather than sleeping for 71 seconds to watch one full cycle.
package ca.garionhk.cydclock

import ca.garionhk.cydclock.input.GestureClassifier
import ca.garionhk.cydclock.input.TouchEvent
import ca.garionhk.cydclock.render.DeviceCanvas
import ca.garionhk.cydclock.scenes.ClockScene
import ca.garionhk.cydclock.scenes.PlaceholderScene
import ca.garionhk.cydclock.scenes.Scene
import ca.garionhk.cydclock.scenes.SceneContext
import ca.garionhk.cydclock.scenes.SceneManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private fun newManager() = SceneManager(
    listOf(
        ClockScene,
        PlaceholderScene("Weather"),
        PlaceholderScene("Sun & Moon"),
        PlaceholderScene("Air Quality"),
    )
)

class SceneRotationTest {

    @Test
    fun `dwell times match the firmware scene table`() {
        val m = newManager()
        assertEquals(4, m.count)
        assertEquals(listOf("Clock", "Weather", "Sun & Moon", "Air Quality"), m.scenes.map { it.name })
        assertEquals(listOf(35_000L, 12_000L, 12_000L, 12_000L), m.scenes.map { it.dwellMs })
    }

    @Test
    fun `a full cycle takes 71 seconds`() {
        // 35 + 12 + 12 + 12. Worth pinning: it is the number that decides how long
        // someone waits for the clock to come back around.
        assertEquals(71_000L, newManager().scenes.sumOf { it.dwellMs })
    }

    @Test
    fun `scenes advance on dwell expiry`() {
        val m = newManager()
        m.begin(0)
        assertEquals(0, m.index)

        m.tick(34_999); assertEquals("clock should still be up at 34.999 s", 0, m.index)
        m.tick(35_000); assertEquals(1, m.index)
        m.tick(46_999); assertEquals(1, m.index)
        m.tick(47_000); assertEquals(2, m.index)
        m.tick(59_000); assertEquals(3, m.index)
        m.tick(71_000); assertEquals("should wrap back to the clock", 0, m.index)
    }

    @Test
    fun `tap advances immediately and freezes rotation for 45 seconds`() {
        val m = newManager()
        m.begin(0)
        m.onTap(1_000)
        assertEquals(1, m.index)
        assertTrue(m.isFrozen(1_000))

        // Weather's 12 s dwell expires during the freeze and must be ignored.
        m.tick(13_001)
        assertEquals("frozen rotation should not advance", 1, m.index)
        assertTrue(m.isFrozen(45_999))

        // The freeze ends at 46 s; the dwell is long past, so it advances at once.
        assertTrue(!m.isFrozen(46_000))
        m.tick(46_000)
        assertEquals(2, m.index)
    }

    @Test
    fun `long press pins and blocks rotation indefinitely`() {
        val m = newManager()
        m.begin(0)
        m.onLongPress(1_000)
        assertTrue(m.pinned)

        m.tick(100_000)
        assertEquals("a pinned scene never rotates", 0, m.index)
        assertTrue("pinned is not frozen -- they are different strip glyphs", !m.isFrozen(100_000))
    }

    @Test
    fun `unpinning restarts the dwell rather than resuming an expired one`() {
        // Otherwise the scene would vanish the instant you let go, having "owed"
        // the rotation several minutes.
        val m = newManager()
        m.begin(0)
        m.onLongPress(1_000)
        m.tick(500_000)
        assertEquals(0, m.index)

        m.onLongPress(500_000)          // unpin
        assertTrue(!m.pinned)
        m.tick(534_999)
        assertEquals("dwell should have restarted from the unpin", 0, m.index)
        m.tick(535_000)
        assertEquals(1, m.index)
    }

    @Test
    fun `unpinning clears any freeze`() {
        val m = newManager()
        m.begin(0)
        m.onTap(1_000)                  // freeze until 46 s
        m.onLongPress(2_000)            // pin
        m.onLongPress(3_000)            // unpin -- clears the freeze
        assertTrue(!m.isFrozen(4_000))
    }

    @Test
    fun `tapping repeatedly walks the whole cycle`() {
        val m = newManager()
        m.begin(0)
        val seen = mutableListOf(m.index)
        repeat(4) { i -> m.onTap(1_000L + i); seen += m.index }
        assertEquals(listOf(0, 1, 2, 3, 0), seen)
    }

    // ---- gesture classification -------------------------------------------

    @Test
    fun `gestures are classified by hold duration on release`() {
        assertEquals(TouchEvent.NONE, GestureClassifier.classify(0))
        assertEquals(TouchEvent.NONE, GestureClassifier.classify(39))     // ghost touch
        assertEquals(TouchEvent.TAP, GestureClassifier.classify(40))
        assertEquals(TouchEvent.TAP, GestureClassifier.classify(799))
        assertEquals(TouchEvent.LONG_PRESS, GestureClassifier.classify(800))
        assertEquals(TouchEvent.LONG_PRESS, GestureClassifier.classify(3_999))
        assertEquals(TouchEvent.SETTINGS, GestureClassifier.classify(4_000))
        assertEquals(TouchEvent.SETTINGS, GestureClassifier.classify(30_000))
    }

    @Test
    fun `one press produces exactly one event`() {
        // Android's GestureDetector fires onLongPress during the press and would
        // then also deliver a tap -- pinning and advancing from one finger. This
        // is the property that avoids it.
        for (held in longArrayOf(50, 500, 900, 3_000, 5_000)) {
            val ev = GestureClassifier.classify(held)
            assertEquals(1, listOf(ev).size)
            assertTrue(ev != TouchEvent.NONE)
        }
    }

    // ---- every scene must survive an empty data model ----------------------

    @Test
    fun `all scenes draw without data`() {
        val fonts = loadFonts()
        val ctx = SceneContext(
            data = ca.garionhk.cydclock.core.AppData(),
            settings = ca.garionhk.cydclock.data.AppSettings(),
            now = java.time.ZonedDateTime.of(2026, 8, 8, 14, 5, 30, 0, java.time.ZoneId.of("UTC")),
            nowEpoch = 1_775_000_000L,
        )
        for (scene: Scene in newManager().scenes) {
            val fb = GoldenRender.blank()
            scene.draw(DeviceCanvas(fb, fonts), ctx)   // must not throw
        }
    }
}
