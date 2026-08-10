// ===========================================================================
// SettingsRepository.kt -- persistence. Port of app/settings.cpp
// ===========================================================================
// NVS namespace "cydcfg" becomes a DataStore file of the same name, and the key
// names are kept too, so the two stores read as the same thing.
//
// One piece of the firmware does not carry over: settings.cpp wrote its `ok`
// sentinel LAST, and gated loading on it, because a power cut mid-write could
// otherwise leave NVS half-populated and readable. DataStore commits atomically,
// so `ok` survives only as the provisioned flag -- it no longer has a second job
// as a torn-write detector.
package ca.garionhk.cydclock.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(name = "cydcfg")

class SettingsRepository(private val context: Context) {

    private object Keys {
        val LAT = doublePreferencesKey("lat")
        val LON = doublePreferencesKey("lon")
        val ZONE = stringPreferencesKey("zone")
        val UNITS = intPreferencesKey("units")
        val PROVISIONED = booleanPreferencesKey("ok")
        val FILL_SCREEN = booleanPreferencesKey("fill")
        val SHOW_OVER_LOCK = booleanPreferencesKey("lockscreen")
    }

    val flow: Flow<AppSettings> = context.settingsStore.data.map { p ->
        val defaults = AppSettings()
        AppSettings(
            latitude = p[Keys.LAT] ?: defaults.latitude,
            longitude = p[Keys.LON] ?: defaults.longitude,
            zoneId = p[Keys.ZONE],           // absent means "follow the device"
            units = p[Keys.UNITS] ?: defaults.units,
            provisioned = p[Keys.PROVISIONED] ?: false,
            fillScreen = p[Keys.FILL_SCREEN] ?: defaults.fillScreen,
            // Falling back to the default rather than a literal is what carries
            // default-ON across an upgrade: an install from before this key
            // existed simply has no value, so it reads true with no migration.
            showOverLockScreen = p[Keys.SHOW_OVER_LOCK] ?: defaults.showOverLockScreen,
        )
    }

    suspend fun save(s: AppSettings) {
        context.settingsStore.edit { p ->
            p[Keys.LAT] = s.latitude
            p[Keys.LON] = s.longitude
            if (s.zoneId != null) p[Keys.ZONE] = s.zoneId else p.remove(Keys.ZONE)
            p[Keys.UNITS] = s.units
            p[Keys.FILL_SCREEN] = s.fillScreen
            p[Keys.SHOW_OVER_LOCK] = s.showOverLockScreen
            p[Keys.PROVISIONED] = s.provisioned
        }
    }
}
