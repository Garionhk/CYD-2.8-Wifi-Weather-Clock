package ca.garionhk.cydclock

import ca.garionhk.cydclock.render.DeviceCanvas
import ca.garionhk.cydclock.render.Framebuffer
import ca.garionhk.cydclock.render.TestPattern
import org.junit.Assert.assertTrue
import org.junit.Test

class TestPatternRenderTest {

    @Test
    fun `render the M1 test pattern for inspection`() {
        val fb = Framebuffer()
        TestPattern.draw(DeviceCanvas(fb))
        val out = GoldenRender.write(fb, "m1_test_pattern", scale = 3)
        assertTrue("expected a PNG at ${out.absolutePath}", out.exists() && out.length() > 0)
    }

    @Test
    fun `the checkerboard alternates every single pixel`() {
        // If the upscale ever starts filtering, this is the pattern that shows it.
        // Assert the source is genuinely 1 px alternating before blaming the scaler.
        val fb = Framebuffer()
        TestPattern.draw(DeviceCanvas(fb))
        for (y in 0 until 48) {
            for (x in 0 until 64) {
                val expected = if ((x + y) and 1 == 0) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
                assertTrue("checkerboard wrong at $x,$y", fb.pixelAt(x, y) == expected)
            }
        }
    }
}
