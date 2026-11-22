package io.github.xiaomibutton.remapper

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.LayoutDirection
import io.github.xiaomibutton.remapper.runtime.RemapperRuntime
import io.github.xiaomibutton.remapper.runtime.RuntimeStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

@Composable
internal fun Dashboard(
    config: AppConfig, status: RuntimeStatus, edit: (GestureSlot) -> Unit, toggle: (Boolean) -> Unit,
    resume: () -> Unit, launchSystem: (Intent) -> Unit, updateNotice: @Composable () -> Unit
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val actionIcons = remember(configuration.uiMode, configuration.densityDpi) { PickerIconCache() }
    val ready = status.shizukuAuthorized
    val mirrored = LocalLayoutDirection.current == LayoutDirection.Rtl
    val modelName = remember { xiaomi18ModelLabel(Build.MODEL, Build.DEVICE) }
    Row(
        Modifier.fillMaxWidth().padding(start = 1.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        IconTile(Mark.BUTTON, modifier = Modifier.size(34.dp))
        Text("Button Remapper", style = MaterialTheme.typography.titleMedium)
    }
    updateNotice()
    Panel {
        Box(
            Modifier.fillMaxWidth().background(Color(0xFF2B100A)),
            contentAlignment = Alignment.CenterStart
        ) {
            Box(Modifier.matchParentSize().graphicsLayer { scaleX = if (mirrored) -1f else 1f }) {
                Image(
                    painterResource(R.drawable.button_hero), contentDescription = null,
                    modifier = Modifier.matchParentSize(), contentScale = ContentScale.Crop
                )
                Box(Modifier.matchParentSize().background(Brush.horizontalGradient(
                    0f to Color(0xCC230D08), 0.5f to Color(0xA5230D08), 0.8f to Color.Transparent
                )))
            }
            Column(
                Modifier.fillMaxWidth(.72f).heightIn(min = 154.dp).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically)
            ) {
                if (modelName != null) {
                    Text(modelName, style = MaterialTheme.typography.labelLarge, color = Color(0xFFFFDBCA))
                }
                Text("AI button", Modifier.semantics { heading() }, color = Color(0xFFFFF8F4),
                    style = MaterialTheme.typography.headlineLarge)
            }
        }
        SettingRow(
            "Remap button", when {
                status.running -> "Custom actions are active"
                status.starting && config.enabled -> "Starting remapping…"
                config.enabled -> "Remapping is paused"
                else -> "Xiaomi default behavior"
            }, onClick = { toggle(!config.enabled) }, checked = config.enabled, enabled = ready && !status.starting,
            trailing = { Switch(checked = config.enabled, onCheckedChange = null, enabled = ready && !status.starting) }
        )
        if (ready && config.enabled && !status.running && !status.starting) {
            PrimaryButton("Resume remapping", resume, Modifier.padding(start = 16.dp, bottom = 14.dp))
        }
    }
    if (!ready) ShizukuSetup(status, launchSystem)
    GroupTitle("Gestures")
    Panel {
        GestureSlot.entries.forEachIndexed { index, slot ->
            val action = config.action(slot)
            val label = if (action.kind == ActionKind.NONE && slot != GestureSlot.SINGLE) "Unassigned"
                else actionLabel(action, context)
            val condition = config.conditions[slot]?.takeUnless { it == TriggerCondition.ALWAYS }
            val description = condition?.let { "$label · ${it.title}" } ?: label
            SettingRow(
                slot.title, description, { edit(slot) }, mark = slot.mark, enabled = ready,
                trailing = if (action.kind != ActionKind.NONE) ({
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        MappedActionIcon(action, actionIcons)
                        if (ready) LineIcon(Mark.NEXT, Modifier.size(16.dp), MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }) else null
            )
            if (index < GestureSlot.entries.lastIndex) RowDivider(start = 68)
        }
    }
    if (ready && !status.running) {
        Panel {
            val events = status.events
            val message = if (events.isEmpty()) status.diagnostics
                else events.take(6).asReversed().joinToString(" · ") { eventName(it.value) }
            SettingRow("Physical button test", message, mark = Mark.PULSE)
            if (status.observing) SecondaryButton("Stop listening", RemapperRuntime::endTest,
                Modifier.padding(start = 16.dp, bottom = 14.dp))
            else if (!status.starting) SecondaryButton("Test button", { RemapperRuntime.test(context) },
                Modifier.padding(start = 16.dp, bottom = 14.dp))
        }
    }
}

@Composable
internal fun UpdateNotice(installed: String, available: String, download: () -> Unit, dismiss: () -> Unit) {
    Panel(color = MaterialTheme.colorScheme.surfaceContainerLow) {
        SettingRow("Update available", "Installed $installed · Available $available", icon = R.drawable.action_open_in_new)
        FlowRow(
            Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            PrimaryButton("Download APK", download)
            TextButton(onClick = dismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("Later") }
        }
    }
}

private fun eventName(value: Int) = when (value) { 0 -> "UP"; 1 -> "DOWN"; 2 -> "REPEAT"; else -> value.toString() }

@Composable
private fun ShizukuSetup(status: RuntimeStatus, launchSystem: (Intent) -> Unit) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    fun openShizuku() {
        context.packageManager.getLaunchIntentForPackage(RemapperRuntime.SHIZUKU_PACKAGE)?.let(launchSystem)
    }
    val instruction = when {
        !status.shizukuInstalled -> "Install and start Shizuku to assign gestures."
        !status.shizukuRunning -> "Start the Shizuku service, then return here."
        status.shizukuPermissionPending -> "Waiting for confirmation."
        else -> "Allow access so the remapper can read the AI button."
    }
    Panel(color = colors.surfaceContainerLow) {
        SettingRow("Shizuku setup", instruction, mark = Mark.LINK)
        FlowRow(
            Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            when {
                !status.shizukuInstalled -> PrimaryButton("Get Shizuku", {
                    launchSystem(Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/guide/setup/")))
                })
                !status.shizukuRunning -> PrimaryButton("Start Shizuku", ::openShizuku)
                else -> {
                    PrimaryButton("Grant Shizuku access", { RemapperRuntime.requestShizuku(context) },
                        enabled = !status.shizukuPermissionPending)
                    TextButton(onClick = ::openShizuku, modifier = Modifier.heightIn(min = 48.dp)) { Text("Open Shizuku") }
                }
            }
        }
        val diagnostic = status.diagnostics
        if (diagnostic.isNotBlank() && status.shizukuRunning && !status.shizukuPermissionPending) {
            SelectionContainer { QuietText(diagnostic, Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp)) }
        }
    }
}

@Composable
internal fun Diagnostics(status: RuntimeStatus, launchSystem: (Intent) -> Unit) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val date = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()) }
    Headline("Diagnostics")
    if (!status.shizukuAuthorized) ShizukuSetup(status, launchSystem)
    else {
        Panel(color = colors.surfaceContainerLow) {
            SettingRow("Input connection", "Shizuku authorized", mark = Mark.LINK)
            RowDivider(start = 68)
            SettingRow("Button status", when {
                status.running -> "Remapping active"
                status.observing -> "Read-only tester active"
                status.starting -> "Connecting to the input source…"
                else -> "Ready to test"
            }, mark = Mark.PULSE)
        }
    }
    if (status.shizukuAuthorized && status.diagnostics.isNotBlank()) SelectionContainer {
        QuietText(status.diagnostics, Modifier.padding(horizontal = 4.dp))
    }
    GroupTitle("Live button tester")
    Panel {
        SettingRow("Target button", "gpio-keys · Linux scan code 689 (0x2B1)", mark = Mark.BUTTON)
        if (status.observing) {
            SecondaryButton("Stop listening", RemapperRuntime::endTest, Modifier.padding(start = 16.dp, bottom = 14.dp))
        } else if (!status.running && !status.starting && status.shizukuAuthorized) {
            QuietText("Listen for up to 60 seconds while the phone keeps its normal button behavior.",
                Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp))
            PrimaryButton("Listen without remapping", { RemapperRuntime.test(context) },
                Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp))
        }
        RowDivider()
        if (status.events.isEmpty()) QuietText(
            when {
                status.running || status.observing -> "Press the physical AI button to see its events."
                status.starting -> "Connecting to the input source…"
                status.shizukuAuthorized -> "Start the listener or enable remapping to see button events."
                else -> "Finish setup to test the button."
            }, Modifier.padding(16.dp)
        ) else Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            status.events.take(12).forEach { event ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
                    Text(eventName(event.value), Modifier.weight(1f), style = MaterialTheme.typography.labelMedium,
                        color = colors.primary, fontFamily = FontFamily.Monospace)
                    Text(date.format(Date(event.wallTimeMs)), style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant, fontFamily = FontFamily.Monospace)
                }
            }
        }
        if (status.lastAction.isNotBlank()) {
            RowDivider()
            SettingRow("Last action", status.lastAction)
        }
    }
    if (status.running || status.observing || status.starting) {
        PrimaryButton("Emergency disable", { RemapperRuntime.stop(context) }, Modifier.fillMaxWidth(), destructive = true)
    }
    GroupTitle("Troubleshooting")
    Panel {
        SettingRow("No AI-button event appears",
            "Stop Key Mapper’s Expert System Bridge if it’s running, then start a fresh test.", icon = R.drawable.action_info)
        RowDivider(start = 68)
        SettingRow("After restarting the phone", "Start Shizuku, then return to the remapper and tap Resume remapping.",
            icon = R.drawable.action_replay)
    }
}

@Composable
private fun TimingControl(title: String, description: String, value: Int, limits: IntRange, step: Int, save: (Int) -> Unit) {
    var position by remember(value) { mutableFloatStateOf(value.toFloat()) }
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 5.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            Text("${position.roundToInt()} ms", Modifier.background(colors.primaryContainer, CircleShape)
                .padding(horizontal = 10.dp, vertical = 5.dp), style = MaterialTheme.typography.labelMedium,
                color = colors.onPrimaryContainer)
        }
        QuietText(description)
        Slider(position, { position = it }, valueRange = limits.first.toFloat()..limits.last.toFloat(),
            steps = (limits.last - limits.first) / step - 1, onValueChangeFinished = { save(position.roundToInt()) },
            colors = SliderDefaults.colors(activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent),
            modifier = Modifier.semantics { contentDescription = title })
    }
}

@Composable
internal fun Preferences(
    config: AppConfig, status: RuntimeStatus, change: ((AppConfig) -> AppConfig) -> Unit, launchSystem: (Intent) -> Unit,
    export: () -> Unit, import: () -> Unit, restore: () -> Unit
) {
    val context = LocalContext.current
    Headline("Settings")
    GroupTitle("Interface")
    Panel {
        SettingRow("Enable diagnostics", "Show the live button tester and troubleshooting tab",
            { change { it.copy(showDiagnostics = !it.showDiagnostics) } }, mark = Mark.PULSE, checked = config.showDiagnostics,
            trailing = { Switch(config.showDiagnostics, onCheckedChange = null) })
    }
    GroupTitle("Gesture timing")
    Panel {
        TimingControl("Multi-press interval", "Maximum gap between taps. At 0 ms, each tap is a single press.",
            config.doubleMs, ConfigJson.doubleRange, 10) { ms -> change { it.copy(doubleMs = ms) } }
        RowDivider()
        TimingControl("Long press", "Hold time before the long-press action runs. At 0 ms, a mapped long press runs on button down.",
            config.longMs, ConfigJson.longRange, 25) { ms -> change { it.copy(longMs = ms) } }
        RowDivider()
        TimingControl("Debounce", "Ignore contact bounce after a release. At 0 ms, this filter is off.",
            config.debounceMs, ConfigJson.debounceRange, 5) { ms -> change { it.copy(debounceMs = ms) } }
        RowDivider()
        SettingRow("Haptic feedback", "Vibrate when a gesture is recognized", { change { it.copy(haptics = !it.haptics) } },
            checked = config.haptics, icon = R.drawable.action_vibration,
            trailing = { Switch(config.haptics, onCheckedChange = null) })
    }
    GroupTitle("Permissions and background")
    Panel {
        SettingRow("UI interactions", "Optional. Let configured app element and shade actions read the active unlocked window.",
            { RemapperRuntime.enableUiActions(context, !status.uiActionsEnabled) }, checked = status.uiActionsEnabled,
            icon = R.drawable.action_accessibility_new, trailing = { Switch(status.uiActionsEnabled, onCheckedChange = null) })
        RowDivider(start = 68)
        SettingRow("Do Not Disturb access", "Required for DND and some silent-mode changes", {
            launchSystem(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
        }, icon = R.drawable.action_do_not_disturb_on)
        RowDivider(start = 68)
        val allowNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
        SettingRow("Notifications", "Optional. Shows the Stop button and actions that need a tap.", {
            if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) allowNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            else launchSystem(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
        }, icon = R.drawable.action_notifications)
        RowDivider(start = 68)
        SettingRow("App info", "In HyperOS app settings, enable "
            + "Background autostart and choose No restrictions for battery use if available.", {
            launchSystem(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + context.packageName)))
        }, icon = R.drawable.action_info)
    }
    MappingOptions(config, change)
    GroupTitle("Configuration")
    Panel {
        SettingRow("Export configuration", "Save gestures and timing to a JSON file", export, icon = R.drawable.action_open_in_new)
        RowDivider(start = 68)
        SettingRow("Import configuration", "Review a saved configuration", import, icon = R.drawable.action_content_paste)
        RowDivider(start = 68)
        SettingRow("Restore Xiaomi default", "Release the button and keep your saved mappings", restore,
            icon = R.drawable.action_replay)
    }
}
