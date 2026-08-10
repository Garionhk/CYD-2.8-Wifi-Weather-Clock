// ===========================================================================
// SettingsScreen.kt -- the settings form. Port of webconfig.cpp's served page
// ===========================================================================
// A native screen, not another canvas scene -- and the firmware agrees with that
// choice, since it handed settings to a browser rather than draw them on the
// panel. A 320x240 raster with a digits-only large font cannot host a soft
// keyboard with IME insets, a searchable list of some six hundred zone ids,
// decimal entry, or the system permission dialog.
//
// Fields map one-to-one from the served form, minus ssid/pass. There is no
// "Save & restart": rendering is a pure function of (AppData, AppSettings, now),
// so Apply is visible on the next 100 ms tick.
package ca.garionhk.cydclock.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import ca.garionhk.cydclock.BuildConfig
import ca.garionhk.cydclock.data.AppSettings
import ca.garionhk.cydclock.data.UNITS_IMPERIAL
import ca.garionhk.cydclock.data.UNITS_METRIC
import kotlinx.coroutines.launch
import java.time.ZoneId

@Composable
fun SettingsScreen(
    settings: AppSettings,
    onApply: (AppSettings) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var form by remember(settings) { mutableStateOf(SettingsForm.from(settings)) }
    var showZonePicker by remember { mutableStateOf(false) }
    var locating by remember { mutableStateOf(false) }
    var locationNote by remember { mutableStateOf<String?>(null) }

    fun runAutofill() {
        scope.launch {
            locating = true
            locationNote = null
            val fix = LocationAutofill.fetch(context)
            locating = false
            if (fix == null) {
                locationNote = "Couldn't get a location. Enter the coordinates by hand."
            } else {
                // Filled in, not saved. Let the user see the numbers and confirm.
                form = form.copy(
                    lat = SettingsForm.coord(fix.first),
                    lon = SettingsForm.coord(fix.second),
                )
                locationNote = "Filled from this device's location."
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (granted.values.any { it }) runAutofill()
        else locationNote = "Location permission denied. Enter the coordinates by hand."
    }

    Scaffold { padding ->
        // A landscape tablet is up to 1920 px wide. Left to fill, the coordinate
        // fields become half a metre long and the section rules run edge to edge,
        // which reads as a broken layout rather than a spacious one.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
            contentAlignment = Alignment.TopCenter,
        ) {
        Column(
            modifier = Modifier
                .widthIn(max = 720.dp)
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp),
        ) {
            Text("Setup", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(16.dp))

            // ---- Location -----------------------------------------------
            SectionHeader("Location")
            Text(
                "Used for weather, air quality, sunrise and sunset. " +
                    "Decimal degrees; south and west are negative.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = form.lat,
                    onValueChange = { form = form.copy(lat = it) },
                    label = { Text("Latitude") },
                    isError = form.latError != null,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = form.lon,
                    onValueChange = { form = form.copy(lon = it) },
                    label = { Text("Longitude") },
                    isError = form.lonError != null,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f),
                )
            }
            (form.latError ?: form.lonError)?.let { ErrorText(it) }

            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(
                    onClick = {
                        if (LocationAutofill.hasPermission(context)) runAutofill()
                        else permissionLauncher.launch(LocationAutofill.PERMISSIONS)
                    },
                    enabled = !locating,
                ) { Text("Use my location") }

                if (locating) {
                    Spacer(Modifier.width(12.dp))
                    CircularProgressIndicator(modifier = Modifier.height(20.dp).width(20.dp))
                }
            }
            locationNote?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, style = MaterialTheme.typography.bodySmall)
            }

            SectionDivider()

            // ---- Time zone ----------------------------------------------
            SectionHeader("Time zone")
            SwitchRow(
                label = "Use this device's time zone",
                checked = form.useDeviceZone,
                onCheckedChange = { form = form.copy(useDeviceZone = it) },
            )
            if (!form.useDeviceZone) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { showZonePicker = true }) {
                    Text(zoneLabel(form.zoneId ?: ZoneId.systemDefault().id))
                }
            } else {
                Text(
                    zoneLabel(ZoneId.systemDefault().id),
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            SectionDivider()

            // ---- Units --------------------------------------------------
            SectionHeader("Units")
            RadioRow(
                label = "Metric  (°C, km/h, hPa)",
                selected = form.units != UNITS_IMPERIAL,
                onSelect = { form = form.copy(units = UNITS_METRIC) },
            )
            RadioRow(
                label = "Imperial  (°F, mph, inHg)",
                selected = form.units == UNITS_IMPERIAL,
                onSelect = { form = form.copy(units = UNITS_IMPERIAL) },
            )

            SectionDivider()

            // ---- Display ------------------------------------------------
            SectionHeader("Display")
            SwitchRow(
                label = "Fill the screen",
                checked = form.fillScreen,
                onCheckedChange = { form = form.copy(fillScreen = it) },
            )
            Text(
                "Off, the clock keeps the original panel's 4:3 shape with black " +
                    "bars either side. On, it uses the whole display: the layout " +
                    "spreads out to fill the width, and the status bar and the " +
                    "setup button move to the screen edges.",
                style = MaterialTheme.typography.bodySmall,
            )

            Spacer(Modifier.height(16.dp))
            SwitchRow(
                label = "Show over the lock screen",
                checked = form.showOverLockScreen,
                onCheckedChange = { form = form.copy(showOverLockScreen = it) },
            )
            Text(
                "On, the clock stays on the screen when the phone locks, and you " +
                    "can still tap it to change scenes. Nothing else is unlocked: " +
                    "the rest of the phone needs your PIN as usual, and pressing " +
                    "Home leaves the clock and shows the lock screen. Off, locking " +
                    "the phone hides the clock until you unlock it.",
                style = MaterialTheme.typography.bodySmall,
            )

            SectionDivider()

            // ---- About --------------------------------------------------
            SectionHeader("About")
            Text(
                "CYD Clock ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Weather and air quality from Open-Meteo (CC BY 4.0).\n" +
                    "Screen fonts from TFT_eSPI (FreeBSD licence), derived from the " +
                    "Adafruit GFX Library (BSD licence).",
                style = MaterialTheme.typography.bodySmall,
            )

            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = { form.toSettings(settings)?.let { onApply(it); onClose() } },
                    enabled = form.valid,
                ) { Text("Apply") }
                TextButton(onClick = onClose) { Text("Cancel") }
            }
            Spacer(Modifier.height(24.dp))
        }
        }
    }

    if (showZonePicker) {
        TimeZonePickerDialog(
            current = form.zoneId,
            onPick = { form = form.copy(zoneId = it, useDeviceZone = false); showZonePicker = false },
            onDismiss = { showZonePicker = false },
        )
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun SectionDivider() {
    Spacer(Modifier.height(20.dp))
    Divider()
    Spacer(Modifier.height(20.dp))
}

@Composable
private fun ErrorText(text: String) {
    Spacer(Modifier.height(4.dp))
    Text(text, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun RadioRow(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onSelect)
        Text(label)
    }
}
