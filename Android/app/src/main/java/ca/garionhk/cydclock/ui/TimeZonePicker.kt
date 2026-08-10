// ===========================================================================
// TimeZonePicker.kt -- replaces webconfig.cpp's 23 POSIX presets
// ===========================================================================
// The firmware offered a dropdown of hand-written POSIX TZ strings
// ("AEST-10AEDT,M10.1.0,M4.1.0/3") because the ESP32's newlib has no timezone
// database, so every DST rule had to be spelled out inline and a zone not on the
// list meant typing one yourself.
//
// Android ships the IANA database. So this is the whole database, searchable,
// and nobody has to write a DST rule again.
package ca.garionhk.cydclock.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId

/** "Europe/London  (UTC+01:00)" -- the offset is what people actually recognise. */
fun zoneLabel(id: String): String = runCatching {
    val offset = ZoneId.of(id).rules.getOffset(Instant.now())
    val text = if (offset.totalSeconds == 0) "UTC" else "UTC$offset"
    "$id  ($text)"
}.getOrDefault(id)

@Composable
fun TimeZonePickerDialog(
    current: String?,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val all = remember { ZoneId.getAvailableZoneIds().sorted() }
    val shown = remember(query, all) {
        if (query.isBlank()) all
        else all.filter { it.contains(query.trim(), ignoreCase = true) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Time zone") },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                LazyColumn(modifier = Modifier.heightIn(max = 360.dp).padding(top = 8.dp)) {
                    items(shown, key = { it }) { id ->
                        ListItem(
                            headlineContent = { Text(zoneLabel(id)) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPick(id) },
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
