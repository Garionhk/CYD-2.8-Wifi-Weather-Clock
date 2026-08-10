// ===========================================================================
// CydTheme.kt -- the Compose theme for the settings screen
// ===========================================================================
// Always dark, and never isSystemInDarkTheme(). This is an appliance on a wall,
// not an app that follows the phone's preference: the clock itself is white on
// black at all hours, and a settings screen that flashed white when someone
// tapped the gear at night would be the brightest thing in the room.
//
// The manifest theme covers the window background, but Compose's MaterialTheme
// defaults to a LIGHT colour scheme when nothing supplies one -- so without this
// the form renders black-on-white inside a black window.
package ca.garionhk.cydclock.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import ca.garionhk.cydclock.core.Theme as PanelTheme
import ca.garionhk.cydclock.core.rgb565

/** Panel palette entries reused so the two surfaces look like one product. */
private val Accent = Color(rgb565(0x07FF))      // COL_ACCENT, the panel's cyan
private val Pin = Color(rgb565(0xFD20))         // COL_PIN, the panel's orange

private val CydDarkColors = darkColorScheme(
    primary = Accent,
    onPrimary = Color.Black,
    secondary = Pin,
    onSecondary = Color.Black,
    background = Color(PanelTheme.COL_BG),
    onBackground = Color.White,
    surface = Color(PanelTheme.COL_STRIP_BG),   // the status strip's grey
    onSurface = Color.White,
    surfaceVariant = Color(PanelTheme.COL_STRIP_BG),
    onSurfaceVariant = Color(PanelTheme.COL_DATE),
    outline = Color(PanelTheme.COL_DIM),
    error = Color(rgb565(0xF800)),              // the same red as a stale-data dot
)

@Composable
fun CydTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = CydDarkColors, content = content)
}
