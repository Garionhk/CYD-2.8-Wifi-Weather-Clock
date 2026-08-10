// ===========================================================================
// Scene.kt -- the scene contract. Port of app/scenes.h
// ===========================================================================
// The firmware split this into onEnter / onTick / onExit because SPI writes are
// expensive and a full repaint every frame was unaffordable. On Android a repaint
// costs nothing, so there is one draw().
//
// The payoff is bigger than the code saved: rendering becomes a pure function of
// (AppData, AppSettings, now). That is what makes a settings change visible on
// the next tick with no restart, and what lets every scene be golden-tested on
// the JVM.
package ca.garionhk.cydclock.scenes

import ca.garionhk.cydclock.core.AppData
import ca.garionhk.cydclock.core.Theme
import ca.garionhk.cydclock.data.AppSettings
import ca.garionhk.cydclock.render.DeviceCanvas
import java.time.ZonedDateTime

data class SceneContext(
    val data: AppData,
    val settings: AppSettings,
    val now: ZonedDateTime,
    val nowEpoch: Long,
    val online: Boolean = false,
    /**
     * Always true on Android -- the system clock is already synchronised, so the
     * firmware's "--:--" / "syncing time..." branches are unreachable. Kept so
     * the scene code still reads like scenes.cpp.
     */
    val clockValid: Boolean = true,
    /**
     * The grid's width in design units: 320 when letterboxed, wider in fill mode
     * (533 on a 20:9 phone). Scenes lay themselves out against this rather than
     * against a constant 320.
     */
    val width: Int = Theme.SCREEN_W,
) {
    /**
     * Spread a horizontal anchor across the grid.
     *
     * Positions scale; the elements themselves do not. So columns and stat
     * cells move apart to use the extra width while the text and icons in them
     * stay the size they were designed at. At width 320 this is the identity, so
     * the firmware's layout is untouched on a 4:3 screen.
     *
     * Use this for anything laid out as an independent column. Do NOT use it for
     * a tightly coupled group -- see [groupOffset].
     */
    fun sx(x: Int): Int = x * width / Theme.SCREEN_W

    /**
     * Shift that centres a group which must stay tight.
     *
     * The clock's four digits are one number, not four columns: spreading them
     * would render "03 : 23" with holes in it. They keep their spacing and the
     * whole block moves to the middle.
     */
    val groupOffset: Int get() = (width - Theme.SCREEN_W) / 2
}

interface Scene {
    val name: String
    val dwellMs: Long
    fun draw(c: DeviceCanvas, ctx: SceneContext)
}
