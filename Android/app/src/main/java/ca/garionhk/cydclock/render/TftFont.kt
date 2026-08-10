// ===========================================================================
// TftFont.kt -- decodes the extracted TFT_eSPI glyph tables
// ===========================================================================
// Asset layout is produced by tools/extract_tft_fonts.py:
//
//   "TFTF", version u8, fontNo u8, height u8, format u8
//   96 x { advance u8, bmpW u8, offset u32, length u16 }     ASCII 32..127
//   blob
//
// Two things here are load-bearing and easy to "fix" into being wrong:
//
//  * advance != bmpW. Font 2's width table carries a +1 spacing column that the
//    bitmap does not contain. textWidth() sums advance; drawing covers bmpW.
//
//  * Fonts 6 and 8 contain only digits and a little punctuation. Every other
//    character points at the shared space glyph, whose bitmap is wider than
//    those characters' advance, and decoding stops at advance*height so the rest
//    is never read. That is what makes them render blank -- and it is exactly
//    why drawDegVal switches to Font 2 for the unit letter. Substituting a
//    fallback font here would silently change textWidth and move every chained
//    x-position in the app.
//
// Zero android.* imports.
package ca.garionhk.cydclock.render

class TftFont(
    val fontNo: Int,
    val height: Int,
    private val format: Int,
    private val advance: IntArray,
    private val bmpW: IntArray,
    private val offset: IntArray,
    private val blob: ByteArray,
) {
    private val cache = arrayOfNulls<ByteArray>(96)

    /** TFT_eSPI treats anything outside 32..127 as a space. */
    private fun index(c: Char): Int {
        val v = c.code
        return if (v in 32..127) v - 32 else 0
    }

    /** The layout width -- what textWidth sums and what drawString advances by. */
    fun charWidth(c: Char): Int = advance[index(c)]

    fun textWidth(s: String): Int {
        var w = 0
        for (c in s) w += charWidth(c)
        return w
    }

    /** How many columns of the cell the glyph actually covers. */
    fun bitmapWidth(c: Char): Int = bmpW[index(c)]

    /** First and last rows of the digit '0' that carry ink, within the cell. */
    private val inkRows: IntRange by lazy {
        val w = bitmapWidth('0')
        if (w <= 0) return@lazy 0 until height
        val m = glyph('0')
        var top = -1
        var bottom = -1
        for (row in 0 until height) {
            var any = false
            for (col in 0 until w) {
                if (m[row * w + col].toInt() != 0) { any = true; break }
            }
            if (any) {
                if (top < 0) top = row
                bottom = row
            }
        }
        if (top < 0) 0 until height else top..bottom
    }

    /**
     * Ink height of the digit '0', measured from the glyph itself. The four fonts
     * fill their declared cells very differently -- 62% for Font 2, 93% for
     * Font 8 -- so this is the number to lay rows out against.
     */
    val inkHeight: Int get() = inkRows.last - inkRows.first + 1

    /** Row of the cell where the digit ink begins. */
    val inkTop: Int get() = inkRows.first

    /**
     * Row-major mask of bitmapWidth x height, 1 = foreground. Decoded once per
     * character and cached.
     */
    fun glyph(c: Char): ByteArray {
        val i = index(c)
        cache[i]?.let { return it }

        val w = bmpW[i]
        val total = w * height
        val mask = ByteArray(total)
        val off = offset[i]

        if (format == FORMAT_RAW) {
            val bytesPerRow = (w + 7) / 8
            for (row in 0 until height) {
                val rowBase = off + row * bytesPerRow
                for (col in 0 until w) {
                    val b = blob[rowBase + (col shr 3)].toInt() and 0xFF
                    // MSB left.
                    mask[row * w + col] = ((b shr (7 - (col and 7))) and 1).toByte()
                }
            }
        } else {
            var p = 0
            var src = off
            // Bounded by total, never by the glyph's byte length -- see above.
            while (p < total) {
                val b = blob[src++].toInt() and 0xFF
                if (b and 0x80 != 0) {
                    val run = (b and 0x7F) + 1
                    val end = minOf(p + run, total)
                    java.util.Arrays.fill(mask, p, end, 1.toByte())
                    p = end
                } else {
                    p = minOf(p + b + 1, total)   // background run: already zero
                }
            }
        }

        cache[i] = mask
        return mask
    }

    companion object {
        const val FORMAT_RAW = 0
        const val FORMAT_RLE = 1

        private const val HEADER = 8
        private const val RECORD = 8
        private const val GLYPHS = 96

        fun parse(bytes: ByteArray): TftFont {
            require(bytes.size > HEADER + GLYPHS * RECORD) { "font asset truncated" }
            require(
                bytes[0] == 'T'.code.toByte() && bytes[1] == 'F'.code.toByte() &&
                    bytes[2] == 'T'.code.toByte() && bytes[3] == 'F'.code.toByte()
            ) { "not a TFTF font asset" }
            require(bytes[4].toInt() == 1) { "unsupported font asset version ${bytes[4]}" }

            val fontNo = bytes[5].toInt() and 0xFF
            val height = bytes[6].toInt() and 0xFF
            val format = bytes[7].toInt() and 0xFF

            val advance = IntArray(GLYPHS)
            val bmpW = IntArray(GLYPHS)
            val offset = IntArray(GLYPHS)
            val blobStart = HEADER + GLYPHS * RECORD

            for (i in 0 until GLYPHS) {
                val p = HEADER + i * RECORD
                advance[i] = bytes[p].toInt() and 0xFF
                bmpW[i] = bytes[p + 1].toInt() and 0xFF
                offset[i] = (bytes[p + 2].toInt() and 0xFF) or
                    ((bytes[p + 3].toInt() and 0xFF) shl 8) or
                    ((bytes[p + 4].toInt() and 0xFF) shl 16) or
                    ((bytes[p + 5].toInt() and 0xFF) shl 24)
            }

            val blob = bytes.copyOfRange(blobStart, bytes.size)
            return TftFont(fontNo, height, format, advance, bmpW, offset, blob)
        }
    }
}

/** The four fonts the app uses, keyed by TFT_eSPI font number. */
class FontSet(private val fonts: Map<Int, TftFont>) {

    operator fun get(fontNo: Int): TftFont =
        fonts[fontNo] ?: error("font $fontNo not loaded")

    companion object {
        val FONT_NUMBERS = intArrayOf(2, 4, 6, 8)

        /**
         * [read] receives an asset name such as "tft_f4.bin". Passing the reader
         * in rather than a Context is what keeps this file android-free and
         * therefore testable on the JVM.
         */
        fun load(read: (String) -> ByteArray): FontSet =
            FontSet(FONT_NUMBERS.associateWith { TftFont.parse(read("tft_f$it.bin")) })
    }
}
