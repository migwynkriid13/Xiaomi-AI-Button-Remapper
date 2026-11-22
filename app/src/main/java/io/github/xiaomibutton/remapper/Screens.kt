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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
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
    resume: () -> Unit, diagnose: () -> Unit) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val actionIcons = remember(config, configuration.uiMode, configuration.densityDpi) { PickerIconCache() }
    Headline("AI button")
    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.primaryContainer) {
        Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            ButtonDiagram()
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("Dedicated button", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(Build.MANUFACTURER + "  ·  " + Build.MODEL, style = MaterialTheme.typography.bodyMedium)
                Text("gpio-keys  /  0x2B1", style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace)
            }
        }
    }
    Panel {
        SettingRow("Remap button", when {
            status.running -> "Custom actions are active"
            config.enabled -> "Remapping is paused"
            else -> "Xiaomi default behavior"
        }, onClick = { toggle(!config.enabled) }, checked = config.enabled, trailing = {
            Switch(checked = config.enabled, onCheckedChange = null)
        })
        if (config.enabled && !status.running) TextButton(onClick = resume,
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
            if (action.kind == ActionKind.NONE) SettingRow(slot.title, label, { edit(slot) }, marks[index])
            else SettingRow(slot.title, label, { edit(slot) }, marks[index], trailing = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MappedActionIcon(action, actionIcons)
                    LineIcon(Mark.NEXT, Modifier.size(16.dp), MaterialTheme.colorScheme.onSurfaceVariant)
                }
            })
            if (index < GestureSlot.entries.lastIndex) HorizontalDivider(Modifier.padding(start = 74.dp),
                color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
    Panel {
        val connection = if (status.accessibilityConnected) "Accessibility connected" else "Accessibility off"
        SettingRow("Input connection", connection + " · " + status.helper, diagnose, Mark.PULSE)
    }
}

@Composable
private fun ButtonDiagram() {
    val ink = MaterialTheme.colorScheme.onPrimaryContainer
    val accent = MaterialTheme.colorScheme.primary
    Canvas(Modifier.size(width = 48.dp, height = 76.dp).semantics { contentDescription = "Side view of the AI button" }) {
        rotate(8f) {
            val x = size.width * 0.18f
            val top = size.height * 0.03f
            val w = size.width * 0.57f
            drawRoundRect(ink, Offset(x, top), Size(w, size.height * 0.92f),
                CornerRadius(8.dp.toPx()), style = Stroke(1.7.dp.toPx()))
            drawRoundRect(accent, Offset(x + w * 0.82f, top + size.height * 0.27f),
                Size(5.dp.toPx(), size.height * 0.27f), CornerRadius(3.dp.toPx()))
            drawLine(ink.copy(alpha = 0.55f), Offset(x + 5.dp.toPx(), size.height * 0.20f),
                Offset(x + 5.dp.toPx(), size.height * 0.77f), 1.dp.toPx())
        }
    }
}

@Composable
internal fun Diagnostics(config: AppConfig, status: RuntimeStatus, launchSystem: (Intent) -> Unit, startExpert: () -> Unit) {
    val context = LocalContext.current
    val date = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()) }
    var notificationDenied by rememberSaveable { mutableStateOf(false) }
    var expertTest by rememberSaveable { mutableStateOf(false) }
    var showExpert by rememberSaveable { mutableStateOf(false) }
    fun beginTest(expert: Boolean) {
        if (expert) RemapperRuntime.testExpert(context) else RemapperRuntime.test(context)
    }
    val requestNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        notificationDenied = !allowed
        if (allowed) beginTest(expertTest)
    }
    fun listen(expert: Boolean) {
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) {
            expertTest = expert
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else beginTest(expert)
    }
    Headline("Diagnostics")
    GroupTitle("Accessibility")
    Panel {
        SettingRow("Accessibility service", if (status.accessibilityConnected) "Connected" else "Off")
        QuietText(if (status.accessibilityConnected) "Start the tester below to check a press, or turn on remapping from Button."
            else "Enable Xiaomi AI Button Remapper in Android Accessibility settings, then press the physical AI button in the live tester.",
            Modifier.padding(start = 18.dp, end = 18.dp, bottom = 6.dp))
        TextButton(onClick = { RemapperRuntime.openAccessibility(context) }, modifier = Modifier.padding(start = 8.dp)) {
            Text(if (status.accessibilityConnected) "Open accessibility settings" else "Enable accessibility service")
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        SettingRow("Input session", status.helper)
        if (status.diagnostics.isNotBlank()) SelectionContainer { QuietText(status.diagnostics,
            Modifier.padding(start = 18.dp, end = 18.dp, bottom = 15.dp)) }
    }
    GroupTitle("Live button tester")
    Panel {
        SettingRow("Target button", "gpio-keys · Linux scan code 689 (0x2B1)")
        SettingRow("Android key code", status.androidKeyCode?.let { KeyEvent.keyCodeToString(it) + " (" + it + ")" }
            ?: "No Android key code observed yet")
        if (status.observing) {
            Button(onClick = { RemapperRuntime.endTest(context) }, modifier = Modifier.padding(start = 15.dp, bottom = 12.dp)) {
                Text("Stop listening")
            }
        } else if (!config.enabled && !status.running && status.accessibilityConnected) {
            QuietText("Listen for up to 60 seconds. The phone keeps its normal button behavior during the test.",
                Modifier.padding(start = 18.dp, end = 18.dp, bottom = 8.dp))
            Button(onClick = { listen(false) }, modifier = Modifier.padding(start = 15.dp, bottom = 12.dp)) {
                Text("Listen without remapping")
            }
        }
        if (notificationDenied) SettingRow("Notification permission required", "Open app info to allow notifications",
            { launchSystem(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + context.packageName))) })
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        if (status.events.isEmpty()) QuietText(
            if (status.running || status.observing) "Press the physical AI button to see its events."
            else "Start the listener or enable remapping to see button events.", Modifier.padding(18.dp))
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
    }
    Button(onClick = { RemapperRuntime.stop(context) }, modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error,
            contentColor = MaterialTheme.colorScheme.onError)) { Text("Emergency disable") }
    GroupTitle("Troubleshooting")
    Panel {
        SettingRow("No button event appears", "HyperOS can reserve a key before Accessibility receives it. Another active key filter can also affect delivery. Check the service status and test again.")
        if (status.accessibilityConnected && !status.running && !status.observing) {
            SettingRow("Alternate Android key", status.calibratedKey ?: "If Android reports no scan code, press the AI button twice to identify a compatible key.")
            if (status.calibrationPrompt.isNotBlank()) {
                QuietText(status.calibrationPrompt, Modifier.padding(horizontal = 18.dp))
                status.calibrationCandidate?.let { candidate ->
                    TextButton(onClick = { RemapperRuntime.confirmCalibration(context) }, modifier = Modifier.padding(start = 8.dp)) {
                        Text("Use " + candidate)
                    }
                }
                TextButton(onClick = { RemapperRuntime.cancelCalibration() }, modifier = Modifier.padding(start = 8.dp)) {
                    Text("Cancel learning")
                }
            } else {
                TextButton(onClick = { RemapperRuntime.beginCalibration(context) }, modifier = Modifier.padding(start = 8.dp)) {
                    Text("Learn alternate key")
                }
                if (status.calibratedKey != null) TextButton(onClick = { RemapperRuntime.clearCalibration(context) },
                    modifier = Modifier.padding(start = 8.dp)) { Text("Clear learned key") }
            }
        }
        SettingRow("An action needs attention", "Check the last action and the notification. Android may require an unlock or a tap to open an activity.")
        SettingRow("After a restart", "Check that the Accessibility service is connected. Return to Button and resume remapping.")
        SettingRow("Device", Build.MANUFACTURER + " " + Build.MODEL + " · Android " + Build.VERSION.RELEASE +
            " (API " + Build.VERSION.SDK_INT + ")")
    }
    GroupTitle("Expert mode")
    Panel {
        SettingRow("Shizuku Expert fallback",
            if (showExpert) "Hide Expert controls" else "Show options if Accessibility cannot receive the AI button",
            { showExpert = !showExpert })
        if (showExpert) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            SettingRow("Shizuku", status.shizuku)
            QuietText("Expert mode uses a privileged helper to filter the physical input device. Start it explicitly for this session.",
                Modifier.padding(start = 18.dp, end = 18.dp, bottom = 8.dp))
            if (!status.available) {
                val manager = remember { context.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api") }
                if (manager != null) TextButton(onClick = { launchSystem(manager) }, modifier = Modifier.padding(start = 8.dp)) { Text("Open Shizuku") }
                TextButton(onClick = { launchSystem(Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/guide/setup/"))) },
                    modifier = Modifier.padding(start = 8.dp)) { Text("Shizuku setup guide") }
            } else if (!status.authorized) {
                TextButton(onClick = { RemapperRuntime.requestPermission() }, modifier = Modifier.padding(start = 8.dp)) {
                    Text("Grant Shizuku access")
                }
            } else if (!status.running && !status.observing) {
                TextButton(onClick = startExpert, modifier = Modifier.padding(start = 8.dp)) { Text("Start Expert remapping") }
                if (!config.enabled) TextButton(onClick = { listen(true) }, modifier = Modifier.padding(start = 8.dp)) {
                    Text("Test Expert input")
                }
            } else QuietText("Stop the current session before starting another.", Modifier.padding(18.dp))
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
internal fun Preferences(config: AppConfig, change: ((AppConfig) -> AppConfig) -> Unit, launchSystem: (Intent) -> Unit,
    export: () -> Unit, import: () -> Unit, restore: () -> Unit) {
    val context = LocalContext.current
    Headline("Settings")
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
        SettingRow("Do Not Disturb access", "Required for DND and some silent-mode changes", {
            launchSystem(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
        })
        SettingRow("App info", "For background reliability, allow notifications. In HyperOS app settings, enable "
            + "Background autostart and choose No restrictions for battery use if available.", {
            launchSystem(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + context.packageName)))
        })
    }
    GroupTitle("Configuration")
    Panel {
        SettingRow("Export configuration", "Save gestures and timing to a JSON file", export)
        SettingRow("Import configuration", "Review a saved configuration", import)
        SettingRow("Restore Xiaomi default", "Release the button and keep your saved mappings", restore)
    }
}

