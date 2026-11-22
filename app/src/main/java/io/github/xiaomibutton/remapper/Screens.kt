package io.github.xiaomibutton.remapper

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.KeyEvent
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
    val ready = status.accessibilityConnected
    val opacity by animateFloatAsState(if (ready) 1f else .45f, label = "Accessibility controls")
    Headline("AI button")
    if (!ready) AccessibilitySetup(launchSystem)
    Column(Modifier.alpha(opacity), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Panel {
            SettingRow("Remap button", when {
                status.running -> "Custom actions are active"
                config.enabled -> "Remapping is paused"
                else -> "Xiaomi default behavior"
            }, onClick = if (ready) ({ toggle(!config.enabled) }) else null, checked = config.enabled, trailing = {
                Switch(checked = config.enabled, onCheckedChange = null, enabled = ready)
            })
            if (ready && config.enabled && !status.running) TextButton(onClick = resume,
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
}

@Composable
private fun AccessibilitySetup(launchSystem: (Intent) -> Unit) {
    val context = LocalContext.current
    val restrictedSettings = Build.VERSION.SDK_INT >= 33
    GroupTitle("Accessibility setup")
    Panel {
        if (restrictedSettings) {
            SettingRow("1. Allow restricted settings",
                "For APK installs, open App info, the page with Uninstall and Permissions. Tap ⋮ in the top right, then Allow restricted settings.",
                { launchSystem(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))) })
            QuietText("If the menu option is missing, try step 2 once. If Android blocks it, return to App info.",
                Modifier.padding(start = 18.dp, end = 18.dp, bottom = 14.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
        SettingRow(if (restrictedSettings) "2. Enable the accessibility service" else "Enable the accessibility service",
            "Open Accessibility, select Xiaomi AI Button Remapper, and turn it on.",
            { RemapperRuntime.openAccessibility(context) })
    }
}

@Composable
internal fun Diagnostics(status: RuntimeStatus, launchSystem: (Intent) -> Unit) {
    val context = LocalContext.current
    val date = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()) }
    Headline("Diagnostics")
    if (!status.accessibilityConnected) AccessibilitySetup(launchSystem)
    else {
        GroupTitle("Accessibility")
        Panel {
            SettingRow("Accessibility service", "Connected", { RemapperRuntime.openAccessibility(context) })
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
        SettingRow("Android key code", status.androidKeyCode?.let { KeyEvent.keyCodeToString(it) + " (" + it + ")" }
            ?: "No Android key code observed yet")
        if (status.observing) {
            Button(onClick = RemapperRuntime::endTest, modifier = Modifier.padding(start = 15.dp, bottom = 12.dp)) {
                Text("Stop listening")
            }
        } else if (!status.running && status.accessibilityConnected) {
            QuietText("Listen for up to 60 seconds. The phone keeps its normal button behavior during the test.",
                Modifier.padding(start = 18.dp, end = 18.dp, bottom = 8.dp))
            Button(onClick = RemapperRuntime::test, modifier = Modifier.padding(start = 15.dp, bottom = 12.dp)) {
                Text("Listen without remapping")
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        if (status.events.isEmpty()) QuietText(
            when {
                status.running || status.observing -> "Press the physical AI button to see its events."
                status.accessibilityConnected -> "Start the listener or enable remapping to see button events."
                else -> "Finish accessibility setup to test the button."
            }, Modifier.padding(18.dp))
        else Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp)) {
            status.events.take(12).forEach { event ->
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(when (event.value) { 0 -> "UP"; 1 -> "DOWN"; 2 -> "REPEAT"; else -> event.value.toString() },
                        style = MaterialTheme.typography.labelLarge, fontFamily = FontFamily.Monospace)
                    Text(date.format(Date(event.wallTimeMs)), style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace)
                }
            }
        }
        if (status.lastAction.isNotBlank()) SettingRow("Last action", status.lastAction)
        status.frameworkInput?.let { SettingRow("Possible button match", it) }
        if (status.observing && status.events.isEmpty()) QuietText(
            if (status.frameworkVolumeSeen) "Android delivered a volume event. Try Learn alternate key below if the AI button stays silent."
            else "Press a volume key once to check event delivery. Its normal action still runs.",
            Modifier.padding(18.dp))
    }
    if (status.running || status.observing) Button(onClick = { RemapperRuntime.stop(context) }, modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error,
            contentColor = MaterialTheme.colorScheme.onError)) { Text("Emergency disable") }
    if (status.accessibilityConnected) {
        GroupTitle("Troubleshooting")
        Panel {
            SettingRow("No button event appears",
                "Keep the screen on and test again. HyperOS may keep the AI button out of the accessibility key stream.")
            if (!status.running && !status.observing) {
                SettingRow("Alternate Android key", status.calibratedKey ?: "Press the AI button twice to confirm an alternate Android key or input device.")
                if (status.calibrationPrompt.isNotBlank()) {
                    QuietText(status.calibrationPrompt, Modifier.padding(horizontal = 18.dp))
                    status.calibrationCandidate?.let { candidate ->
                        TextButton(onClick = RemapperRuntime::confirmCalibration, modifier = Modifier.padding(start = 8.dp)) {
                            Text("Use " + candidate)
                        }
                    }
                    TextButton(onClick = RemapperRuntime::cancelCalibration, modifier = Modifier.padding(start = 8.dp)) {
                        Text("Cancel learning")
                    }
                } else {
                    TextButton(onClick = RemapperRuntime::beginCalibration, modifier = Modifier.padding(start = 8.dp)) {
                        Text("Learn alternate key")
                    }
                    if (status.calibratedKey != null) TextButton(onClick = RemapperRuntime::clearCalibration,
                        modifier = Modifier.padding(start = 8.dp)) { Text("Clear learned key") }
                }
            }
            SettingRow("Device", Build.MANUFACTURER + " " + Build.MODEL + " · Android " + Build.VERSION.RELEASE +
                " (API " + Build.VERSION.SDK_INT + ")")
        }
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
        TimingControl("Multi-press interval", "Maximum gap between taps. Single presses wait only when double or triple press is assigned.",
            config.doubleMs, ConfigJson.doubleRange, 10) { ms ->
            change { it.copy(doubleMs = ms) }
        }
        TimingControl("Long press", "Hold time before the long-press action runs.", config.longMs, ConfigJson.longRange, 25) { ms ->
            change { it.copy(longMs = ms) }
        }
        TimingControl("Debounce", "Ignore contact bounce after a release.", config.debounceMs, ConfigJson.debounceRange, 5) { ms ->
            change { it.copy(debounceMs = ms) }
        }
        SettingRow("Haptic feedback", "Vibrate when a gesture is recognized", { change { it.copy(haptics = !it.haptics) } },
            checked = config.haptics, trailing = { Switch(config.haptics, onCheckedChange = null) })
    }
    GroupTitle("Permissions and background")
    Panel {
        SettingRow("UI interactions", "Let configured app element and shade actions read the active unlocked window",
            { RemapperRuntime.enableUiActions(context, !status.uiActionsEnabled) }, checked = status.uiActionsEnabled,
            trailing = { Switch(status.uiActionsEnabled, onCheckedChange = null) })
        SettingRow("Do Not Disturb access", "Required for DND and some silent-mode changes", {
            launchSystem(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
        })
        val allowNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
        SettingRow("Notifications", "Optional. Show a Stop action and prompts for actions that need a tap.", {
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
