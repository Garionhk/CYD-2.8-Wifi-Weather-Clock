// ===========================================================================
// GoldenRender.kt -- write a framebuffer out as a PNG from a JVM test
// ===========================================================================
// This is the harness the scene golden tests use later. It works because the
// renderer carries zero android.* imports: Framebuffer, DeviceCanvas, the fonts,
// the scenes and the sun/moon math all run under plain JUnit, so a full 320x240
// frame can be rasterised and inspected without a device or an emulator.
//
// The PNG is encoded by hand rather than through ImageIO, because Android unit
// tests compile against android.jar and neither java.awt nor javax.imageio is
// visible there. java.util.zip is, and a truecolour PNG is only four chunks, so
// this is less trouble than fighting the classpath -- and it keeps the harness
// working regardless of how AGP arranges the unit-test bootclasspath later.
package ca.garionhk.cydclock

import ca.garionhk.cydclock.core.Theme
import ca.garionhk.cydclock.render.Framebuffer
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.CRC32
import java.util.zip.Deflater

object GoldenRender {

    /** Writes to app/build/golden/<name>.png and returns the file. */
    fun write(fb: Framebuffer, name: String, scale: Int = 1): File {
        val w = fb.width * scale
        val h = fb.height * scale

        // Raw scanlines, each prefixed with PNG filter type 0 (None). Nearest
        // neighbour upscale, so the PNG shows exactly what the panel would.
        val raw = ByteArray(h * (1 + w * 3))
        var p = 0
        for (y in 0 until h) {
            raw[p++] = 0
            val sy = y / scale
            for (x in 0 until w) {
                val c = fb.pixelAt(x / scale, sy)
                raw[p++] = ((c shr 16) and 0xFF).toByte()
                raw[p++] = ((c shr 8) and 0xFF).toByte()
                raw[p++] = (c and 0xFF).toByte()
            }
        }

        val png = ByteArrayOutputStream()
        png.write(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10))

        val ihdr = ByteArrayOutputStream().apply {
            writeInt(w); writeInt(h)
            write(8)      // bit depth
            write(2)      // colour type 2 = truecolour RGB
            write(0); write(0); write(0)   // deflate / adaptive filtering / no interlace
        }
        png.writeChunk("IHDR", ihdr.toByteArray())
        png.writeChunk("IDAT", deflate(raw))
        png.writeChunk("IEND", ByteArray(0))

        val dir = File("build/golden").apply { mkdirs() }
        val out = File(dir, "$name.png")
        out.writeBytes(png.toByteArray())
        return out
    }

    /** A framebuffer pre-cleared to the panel background. */
    fun blank(): Framebuffer = Framebuffer().apply { fillScreen(Theme.COL_BG) }

    // ---- PNG plumbing -----------------------------------------------------

    private fun ByteArrayOutputStream.writeInt(v: Int) {
        write((v ushr 24) and 0xFF); write((v ushr 16) and 0xFF)
        write((v ushr 8) and 0xFF); write(v and 0xFF)
    }

    private fun ByteArrayOutputStream.writeChunk(type: String, data: ByteArray) {
        writeInt(data.size)
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        write(typeBytes); write(data)
        val crc = CRC32().apply { update(typeBytes); update(data) }
        writeInt(crc.value.toInt())
    }

    /** zlib stream (not raw deflate) -- PNG's IDAT expects the zlib header. */
    private fun deflate(data: ByteArray): ByteArray {
        val d = Deflater(Deflater.BEST_COMPRESSION)
        d.setInput(data)
        d.finish()
        val out = ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        while (!d.finished()) {
            val n = d.deflate(buf)
            out.write(buf, 0, n)
        }
        d.end()
        return out.toByteArray()
    }
}
