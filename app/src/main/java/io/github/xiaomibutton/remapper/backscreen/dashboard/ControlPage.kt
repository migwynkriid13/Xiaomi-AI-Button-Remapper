package io.github.xiaomibutton.remapper.backscreen.dashboard

import android.app.Activity
import android.bluetooth.BluetoothManager
import android.content.*
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.telephony.TelephonyManager
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.xiaomibutton.remapper.*
import io.github.xiaomibutton.remapper.R
import io.github.xiaomibutton.remapper.backscreen.core.*
import kotlinx.coroutines.*

@Composable
internal fun ControlsPage(
    a: Activity,
    s: DashboardStore,
    locked: Boolean,
    now: Long,
    message: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val audio = a.getSystemService(AudioManager::class.java)
    var recording by remember { mutableStateOf<Job?>(null) }
    fun perform(work: suspend () -> String) {
        scope.launch { message(resultMessage(work)) }
    }
    Section("Display") {
        val automatic = s.value("panelBrightness").isBlank()
        DashToggle("Automatic brightness", automatic) {
            s.set("panelBrightness", if (it) "" else ".5")
        }
        if (!automatic)
            Slider(
                s.value("panelBrightness").toFloatOrNull() ?: .5f,
                { s.set("panelBrightness", it.coerceIn(.03f, 1f).toString()) },
                valueRange = .03f..1f,
            )
        if (!locked)
            Row {
                TextButton(
                    {
                        perform {
                            RearCapture.photo(a)
                            "Saved rear screenshot"
                        }
                    },
                ) {
                    Text("Screenshot")
                }
                TextButton(
                    {
                        if (recording != null) recording?.cancel()
                        else
                            recording = scope.launch {
                                try {
                                    RearCapture.record(a)
                                    message("Saved rear recording")
                                } catch (e: Exception) {
                                    if (e !is CancellationException)
                                        message(e.message ?: "Recording stopped")
                                } finally {
                                    recording = null
                                }
                            }
                    },
                ) {
                    Text(if (recording == null) "Record 1 min" else "Stop recording")
                }
            }
    }
    Section("Quick controls") {
        val silent = audio.ringerMode == AudioManager.RINGER_MODE_SILENT
        DashToggle("Silent mode", silent) { on ->
            perform {
                audio.ringerMode =
                    if (on) AudioManager.RINGER_MODE_SILENT else AudioManager.RINGER_MODE_NORMAL
                "Sound mode updated"
            }
        }
        if (!locked)
            for ((name, label) in
                listOf("wifi" to "Wi-Fi", "bluetooth" to "Bluetooth", "data" to "Mobile data")) {
                val state =
                    remember(now / 1500, name) {
                        runCatching {
                            when (name) {
                                "wifi" ->
                                    a.applicationContext
                                        .getSystemService(WifiManager::class.java)
                                        .isWifiEnabled
                                "bluetooth" ->
                                    if (a.checkSelfPermission(
                                        android.Manifest.permission.BLUETOOTH_CONNECT,
                                    ) == android.content.pm.PackageManager.PERMISSION_GRANTED)
                                        a.getSystemService(BluetoothManager::class.java)
                                            .adapter
                                            ?.isEnabled
                                    else null
                                else ->
                                    a.getSystemService(TelephonyManager::class.java).isDataEnabled
                            }
                        }
                            .getOrNull()
                    }
                if (state != null)
                    DashToggle(label, state) { on ->
                        perform {
                            check(!PhoneLock.isLocked(a))
                            withContext(Dispatchers.IO) { RearCommands.setRadio(name, on) }
                        }
                    }
                else {
                    Text(label)
                    Row {
                        for (on in listOf(true, false)) TextButton(
                            {
                                perform {
                                    check(!PhoneLock.isLocked(a))
                                    withContext(Dispatchers.IO) { RearCommands.setRadio(name, on) }
                                }
                            },
                        ) {
                            Text(if (on) "Turn on" else "Turn off")
                        }
                    }
                }
            }
    }
    val icons = remember { PickerIconCache() }
    for (row in s.pins().filter { !locked || s.flag("locked.$it") }.chunked(2)) Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        row.forEach { id ->
            val spec = s.action(id) ?: return@forEach
            FilledTonalButton({ perform { runTile(s, id) } }, Modifier.weight(1f)) {
                val icon =
                    when (s.value("icon.$id")) {
                        "Music" -> R.drawable.action_music_note
                        "Light" -> R.drawable.action_flashlight_on
                        "Home" -> R.drawable.action_home
                        else -> R.drawable.action_tune
                    }
                if (s.value("icon.$id", "Automatic") == "Automatic") MappedActionIcon(spec, icons)
                else Icon(androidx.compose.ui.res.painterResource(icon), null, Modifier.size(18.dp))
                Text(s.value("label.$id", spec.label.ifBlank { spec.kind.title }), maxLines = 2)
            }
        }
    }
}
