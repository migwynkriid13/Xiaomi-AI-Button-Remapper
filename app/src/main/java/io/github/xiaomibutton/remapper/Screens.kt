package io.github.xiaomibutton.remapper

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.xiaomibutton.remapper.runtime.RemapperRuntime
import io.github.xiaomibutton.remapper.runtime.RuntimeStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

@Composable
internal fun Dashboard(config: AppConfig, status: RuntimeStatus, edit: (GestureSlot) -> Unit, toggle: (Boolean) -> Unit,
    resume: () -> Unit, launchSystem: (Intent) -> Unit) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val actionIcons = remember(config, configuration.uiMode, configuration.densityDpi) { PickerIconCache() }
    val ready = status.shizukuAuthorized
    val opacity by animateFloatAsState(if (ready) 1f else .45f, label = "Remap controls")
    Headline("AI button")
    if (!ready) ShizukuSetup(status, launchSystem)
    Column(Modifier.alpha(opacity), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Panel {
            SettingRow("Remap button", when {
                status.running -> "Custom actions are active"
                status.starting && config.enabled -> "Starting remapping…"
                config.enabled -> "Remapping is paused"
                else -> "Xiaomi default behavior"
            }, onClick = if (ready && !status.starting) ({ toggle(!config.enabled) }) else null, checked = config.enabled, trailing = {
                Switch(checked = config.enabled, onCheckedChange = null, enabled = ready && !status.starting)
            })
            if (ready && config.enabled && !status.running && !status.starting) TextButton(onClick = resume,
                modifier = Modifier.padding(start = 8.dp, bottom = 5.dp)) { Text("Resume remapping") }
        }
        GroupTitle("Gestures")
        Panel {
            val marks = listOf(Mark.SINGLE, Mark.DOUBLE, Mark.LONG, Mark.TRIPLE)
            GestureSlot.entries.forEachIndexed { index, slot ->
                val action = config.action(slot)
                val label = when {
                    action.kind == ActionKind.NONE && slot != GestureSlot.SINGLE -> "Unassigned"
                    else -> actionLabel(action, context)
                }
                val description = config.conditions[slot]?.takeUnless { it == TriggerCondition.ALWAYS }
                    ?.let { "$label · ${it.title}" } ?: label
                val click = if (ready) ({ edit(slot) }) else null
                if (action.kind == ActionKind.NONE) SettingRow(slot.title, description, click, marks[index])
                else SettingRow(slot.title, description, click, marks[index], trailing = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MappedActionIcon(action, actionIcons)
                        if (ready) LineIcon(Mark.NEXT, Modifier.size(16.dp), MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                })
                if (index < GestureSlot.entries.lastIndex) HorizontalDivider(Modifier.padding(start = 74.dp),
                    color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
    if (ready && !status.running) {
        Panel {
            val events = status.events
            val message = if (events.isEmpty()) status.diagnostics
            else events.asReversed().takeLast(6).joinToString(" · ") { eventName(it.value) }
            SettingRow("Physical button test", message)
            if (status.observing) TextButton(onClick = RemapperRuntime::endTest, modifier = Modifier.padding(start = 8.dp)) {
                Text("Stop listening")
            } else if (!status.starting) TextButton(onClick = { RemapperRuntime.test(context) }, modifier = Modifier.padding(start = 8.dp)) {
                Text("Test button")
            }
        }
    }
}

private fun eventName(value: Int) = when (value) { 0 -> "UP"; 1 -> "DOWN"; 2 -> "REPEAT"; else -> value.toString() }

@Composable
private fun ShizukuSetup(status: RuntimeStatus, launchSystem: (Intent) -> Unit) {
    val context = LocalContext.current
    GroupTitle("Shizuku setup")
    Panel {
        if (!status.shizukuInstalled) {
            SettingRow("Get Shizuku", "Install and start Shizuku, then return here.", { launchSystem(Intent(Intent.ACTION_VIEW,
                Uri.parse("https://shizuku.rikka.app/guide/setup/"))) })
        } else if (!status.shizukuRunning) {
            SettingRow("Start Shizuku", "Open Shizuku, start its service and return to the remapper.", {
                context.packageManager.getLaunchIntentForPackage(RemapperRuntime.SHIZUKU_PACKAGE)?.let(launchSystem)
            })
        } else {
            SettingRow("Grant Shizuku access", if (status.shizukuPermissionPending) "Waiting for confirmation"
                else "Allow the remapper to test the physical AI button.", if (status.shizukuPermissionPending) null
                else ({ RemapperRuntime.requestShizuku(context) }))
            TextButton(onClick = { context.packageManager.getLaunchIntentForPackage(RemapperRuntime.SHIZUKU_PACKAGE)?.let(launchSystem) },
                modifier = Modifier.padding(start = 8.dp)) { Text("Open Shizuku") }
        }
        if (status.diagnostics.isNotBlank()) QuietText(status.diagnostics,
            Modifier.padding(start = 18.dp, end = 18.dp, bottom = 12.dp))
    }
}

@Composable
internal fun Diagnostics(status: RuntimeStatus, launchSystem: (Intent) -> Unit) {
    val context = LocalContext.current
    val date = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()) }
    Headline("Diagnostics")
    if (!status.shizukuAuthorized) ShizukuSetup(status, launchSystem)
    else {
        GroupTitle("Input")
        Panel {
            SettingRow("Shizuku", "Authorized")
            SettingRow("Button status", when {
                status.running -> "Remapping active"
                status.observing -> "Read-only tester active"
                else -> "Ready to test"
            })
        }
    }
    SelectionContainer { QuietText(status.diagnostics, Modifier.padding(horizontal = 4.dp)) }
    GroupTitle("Live button tester")
    Panel {
        SettingRow("Target button", "gpio-keys · Linux scan code 689 (0x2B1)")
        if (status.observing) {
            Button(onClick = RemapperRuntime::endTest, modifier = Modifier.padding(start = 15.dp, bottom = 12.dp)) {
                Text("Stop listening")
            }
        } else if (!status.running && !status.starting && status.shizukuAuthorized) {
            QuietText("Listen for up to 60 seconds. The phone keeps its normal button behavior during the test.",
                Modifier.padding(start = 18.dp, end = 18.dp, bottom = 8.dp))
            Button(onClick = { RemapperRuntime.test(context) }, modifier = Modifier.padding(start = 15.dp, bottom = 12.dp)) {
                Text("Listen without remapping")
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        if (status.events.isEmpty()) QuietText(
            when {
                status.running || status.observing -> "Press the physical AI button to see its events."
                status.starting -> "Connecting to the input source…"
                status.shizukuAuthorized -> "Start the listener or enable remapping to see button events."
                else -> "Finish setup to test the button."
            }, Modifier.padding(18.dp))
        else Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp)) {
            status.events.take(12).forEach { event ->
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(eventName(event.value),
                        style = MaterialTheme.typography.labelLarge, fontFamily = FontFamily.Monospace)
                    Text(date.format(Date(event.wallTimeMs)), style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace)
                }
            }
        }
        if (status.lastAction.isNotBlank()) SettingRow("Last action", status.lastAction)
    }
    if (status.running || status.observing || status.starting) Button(onClick = { RemapperRuntime.stop(context) }, modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error,
            contentColor = MaterialTheme.colorScheme.onError)) { Text("Emergency disable") }
    GroupTitle("Troubleshooting")
    Panel {
        SettingRow("No AI-button event appears",
            "Stop Key Mapper’s Expert System Bridge if it’s running, then start a fresh test.")
        SettingRow("After restarting the phone", "Start Shizuku, then return to the remapper and tap Resume remapping.")
    }
}

@Composable
private fun TimingControl(title: String, description: String, value: Int, limits: IntRange, step: Int, save: (Int) -> Unit) {
    var position by remember(value) { mutableFloatStateOf(value.toFloat()) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(position.roundToInt().toString() + " ms", style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary)
        }
        QuietText(description)
        Slider(position, { position = it }, valueRange = limits.first.toFloat()..limits.last.toFloat(),
            steps = (limits.last - limits.first) / step - 1, onValueChangeFinished = { save(position.roundToInt()) },
            modifier = Modifier.semantics { contentDescription = title })
    }
}

@Composable
internal fun Preferences(config: AppConfig, status: RuntimeStatus, change: ((AppConfig) -> AppConfig) -> Unit, launchSystem: (Intent) -> Unit,
    export: () -> Unit, import: () -> Unit, restore: () -> Unit) {
    val context = LocalContext.current
    Headline("Settings")
    GroupTitle("Interface")
    Panel {
        SettingRow("Enable diagnostics", "Show the live button tester and troubleshooting tab",
            { change { it.copy(showDiagnostics = !it.showDiagnostics) } }, checked = config.showDiagnostics,
            trailing = { Switch(config.showDiagnostics, onCheckedChange = null) })
    }
    GroupTitle("Gesture timing")
    Panel {
        TimingControl("Multi-press interval", "Maximum gap between taps. At 0 ms, each tap is a single press.",
            config.doubleMs, ConfigJson.doubleRange, 10) { ms ->
            change { it.copy(doubleMs = ms) }
        }
        TimingControl("Long press", "Hold time before the long-press action runs. At 0 ms, a mapped long press runs on button down.",
            config.longMs, ConfigJson.longRange, 25) { ms ->
            change { it.copy(longMs = ms) }
        }
        TimingControl("Debounce", "Ignore contact bounce after a release. At 0 ms, this filter is off.",
            config.debounceMs, ConfigJson.debounceRange, 5) { ms ->
            change { it.copy(debounceMs = ms) }
        }
        SettingRow("Haptic feedback", "Vibrate when a gesture is recognized", { change { it.copy(haptics = !it.haptics) } },
            checked = config.haptics, trailing = { Switch(config.haptics, onCheckedChange = null) })
    }
    GroupTitle("Permissions and background")
    Panel {
        SettingRow("UI interactions", "Optional. Let configured app element and shade actions read the active unlocked window.",
            { RemapperRuntime.enableUiActions(context, !status.uiActionsEnabled) }, checked = status.uiActionsEnabled,
            trailing = { Switch(status.uiActionsEnabled, onCheckedChange = null) })
        SettingRow("Do Not Disturb access", "Required for DND and some silent-mode changes", {
            launchSystem(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
        })
        val allowNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
        SettingRow("Notifications", "Optional. Shows the Stop button and actions that need a tap.", {
            if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) allowNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            else launchSystem(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
        })
        SettingRow("App info", "In HyperOS app settings, enable "
            + "Background autostart and choose No restrictions for battery use if available.", {
            launchSystem(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + context.packageName)))
        })
    }
    MappingOptions(config, change)
    GroupTitle("Configuration")
    Panel {
        SettingRow("Export configuration", "Save gestures and timing to a JSON file", export)
        SettingRow("Import configuration", "Review a saved configuration", import)
        SettingRow("Restore Xiaomi default", "Release the button and keep your saved mappings", restore)
    }
}
