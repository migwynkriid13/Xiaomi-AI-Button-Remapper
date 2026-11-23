package io.github.xiaomibutton.remapper.backscreen.dashboard

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.github.xiaomibutton.remapper.*
import io.github.xiaomibutton.remapper.R
import io.github.xiaomibutton.remapper.backscreen.battery.BatterySettings
import io.github.xiaomibutton.remapper.backscreen.camera.CameraSettings
import io.github.xiaomibutton.remapper.backscreen.core.FeatureSettings
import io.github.xiaomibutton.remapper.backscreen.core.KeeperService
import io.github.xiaomibutton.remapper.backscreen.core.ShizukuState
import io.github.xiaomibutton.remapper.backscreen.mirror.MirrorSettings
import io.github.xiaomibutton.remapper.backscreen.mirror.QuickSwitchTile
import io.github.xiaomibutton.remapper.backscreen.notifications.NotificationAccess
import io.github.xiaomibutton.remapper.backscreen.notifications.NotificationSettings
import io.github.xiaomibutton.remapper.backscreen.notifications.RearNotificationListener
import io.github.xiaomibutton.remapper.backscreen.rear.RearHostActivity
import io.github.xiaomibutton.remapper.backscreen.ui.Feature
import io.github.xiaomibutton.remapper.backscreen.wallpaper.WallpaperSettings
import kotlinx.coroutines.delay

private enum class LayerSettings(val title: String, val icon: Int, val page: Page? = null) {
    BACKGROUND("Background", R.drawable.rear_ic_image),
    GLANCE("Clock and glance", R.drawable.action_alarm),
    MUSIC("Now playing", R.drawable.action_music_note, Page.MUSIC),
    LIVE("Live activities and inbox", R.drawable.action_view_agenda, Page.LIVE),
    CONTROLS("Control Centre", R.drawable.action_tune, Page.CONTROLS),
    APPS("App handoff", R.drawable.action_open_in_new, Page.APPS),
    SHARED("Pinned notes and photos", R.drawable.action_open_with, Page.SHARED),
    CONNECTIONS("Connected cards and rings", R.drawable.action_link, Page.CONNECTIONS),
    SHORTCUTS("Overlay shortcuts", R.drawable.action_view_carousel),
    AMBIENT("Always-on and pocket mode", R.drawable.action_nightlight),
    PROFILES("Automatic profiles", R.drawable.action_alarm),
}

@Composable
internal fun DashToggle(title: String, on: Boolean, change: (Boolean) -> Unit) {
    SettingRow(
        title,
        onClick = { change(!on) },
        checked = on,
        trailing = {
            Switch(checked = on, onCheckedChange = null)
        })
}

@Composable
internal fun Options(
    title: String,
    current: String,
    choices: List<String>,
    change: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }) { Text("$title · $current") }
        DropdownMenu(open, { open = false }) {
            choices.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = {
                        change(option)
                        open = false
                    },
                )
            }
        }
    }
}

@Composable
internal fun Field(s: DashboardStore, key: String, title: String, fallback: String = "") {
    var value by remember(s, key) { mutableStateOf(s.value(key, fallback)) }
    OutlinedTextField(
        value,
        {
            value = it
            s.set(key, it)
        },
        Modifier.fillMaxWidth(),
        label = { Text(title) },
        singleLine = true,
    )
}

@Composable
internal fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        Modifier.fillMaxWidth(),
        shape = PanelShape,
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
internal fun DashboardEditor(
    modifier: Modifier,
    model: String?,
    openLegacy: (Feature, Int) -> Unit,
) {
    val context = LocalContext.current
    val s = remember(context) { DashboardStore(context) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val displayOn by
        produceState(WallpaperSettings.isEnabled(context), lifecycle) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) {
                    value = WallpaperSettings.isEnabled(context)
                    delay(500)
                }
            }
        }
    val shizuku by
        produceState(ShizukuState.read(context), lifecycle) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) {
                    value = ShizukuState.read(context)
                    delay(500)
                }
            }
        }
    var group by rememberSaveable { mutableStateOf<LayerSettings?>(null) }
    var previewCards by rememberSaveable { mutableStateOf(false) }
    var previewInbox by rememberSaveable { mutableStateOf(false) }
    var picking by rememberSaveable { mutableStateOf("") }
    val nearby = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val calendar = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    var fileKey by rememberSaveable { mutableStateOf("") }
    val file =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null && fileKey.isNotEmpty()) {
                runCatching {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                }
                    .onSuccess {
                        s.set(fileKey, uri.toString())
                        if (fileKey == "backdrop") applyToWallpaper(context, true)
                    }
            }
        }
    fun fileFor(key: String, type: String) {
        fileKey = key
        file.launch(arrayOf(type))
    }
    if (picking.isNotEmpty()) {
        BackHandler { picking = "" }
        ActionPicker(
            GestureSlot.SINGLE,
            s.action(picking) ?: ActionSpec(ActionKind.NONE),
            { picking = "" },
        ) {
            s.action(picking, it)
            if (picking.startsWith("tile.") && picking !in s.pins())
                s.save("tiles", s.pins() + picking)
            picking = ""
        }
        return
    }
    BackHandler(group != null) { group = null }
    val selected = group
    val previewPage =
        when {
            selected == LayerSettings.GLANCE && previewCards -> Page.HOME
            selected == LayerSettings.LIVE && previewInbox -> Page.INBOX
            else -> selected?.page
        }
    val showAmbient = selected == LayerSettings.AMBIENT && s.flag("Always-on")
    val showShortcuts = selected == LayerSettings.SHORTCUTS
    val previewMode =
        previewPage?.title
            ?: when {
                showAmbient -> "Always-on"
                showShortcuts -> "Shortcuts"
                else -> "Wallpaper"
            }
    val editor: @Composable ColumnScope.() -> Unit = {
        key(selected) {
            if (selected == null) {
                DashboardSettingsList(
                    s,
                    displayOn,
                    shizuku,
                    openLegacy,
                    open = { group = it },
                )
            } else
                when (selected) {
                    LayerSettings.GLANCE -> {
                        GlanceSettings(s, ::fileFor)
                        if (!s.glanceClock())
                            Panel {
                                SettingRow(
                                    "Gallery clock",
                                    onClick = {
                                        openLegacy(Feature.WALLPAPER, R.string.rear_edit_clock)
                                    },
                                    icon = R.drawable.action_alarm)
                            }
                        Section("Glance cards") {
                            OverlayToggle(s, Page.HOME)
                            TextButton({ previewCards = !previewCards }) {
                                Text(
                                    if (previewCards) "Preview wallpaper"
                                    else "Preview glance cards")
                            }
                            Ordered(
                                s,
                                "cards",
                                DashboardStore.CARD_NAMES,
                                DashboardStore.DEFAULT_CARDS,
                            ) {
                                it
                            }
                        }
                        Section("Card behavior") {
                            Options(
                                "Card size",
                                s.value("size", "Compact"),
                                listOf("Compact", "Expanded"),
                            ) {
                                s.set("size", it)
                            }
                            for (card in s.cards()) {
                                Text(card, style = MaterialTheme.typography.labelLarge)
                                Row {
                                    TextButton({ picking = "tap.$card" }) { Text("Tap action") }
                                    TextButton({ picking = "hold.$card" }) {
                                        Text("Hold action")
                                    }
                                }
                            }
                            Button({ nearby.launch(Manifest.permission.BLUETOOTH_CONNECT) }) {
                                Text("Allow accessory information")
                            }
                            Button({ calendar.launch(Manifest.permission.READ_CALENDAR) }) {
                                Text("Allow calendar access")
                            }
                            DashToggle(
                                "Show event titles when unlocked",
                                s.flag("eventTitles", true),
                            ) {
                                s.set("eventTitles", it)
                            }
                        }
                    }
                    LayerSettings.MUSIC -> {
                        Section("Player") {
                            OverlayToggle(s, Page.MUSIC)
                            Options(
                                "Artwork",
                                s.value("art", "Cover"),
                                listOf("Cover", "Record"),
                            ) {
                                s.set("art", it)
                            }
                            DashToggle("Synced lyrics from LRCLIB", s.flag("lyrics")) {
                                s.set("lyrics", it)
                            }
                            Text(
                                "Lyrics lookup shares the song title, artist and album with LRCLIB.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            for (key in
                                listOf(
                                    "Title",
                                    "Artist",
                                    "Transport",
                                    "Progress",
                                    "Volume",
                                    "Lyrics",
                                )) colorSetting(s, key)
                        }
                        Section("Access") { MediaAccess(s) }
                    }
                    LayerSettings.LIVE -> {
                        Section("Live activities and inbox") {
                            OverlayToggle(s, Page.LIVE)
                            OverlayToggle(s, Page.INBOX)
                            TextButton({ previewInbox = !previewInbox }) {
                                Text(
                                    if (previewInbox) "Preview live activities"
                                    else "Preview inbox")
                            }
                            DashToggle(
                                "Allow call and alarm sliders while locked",
                                s.flag("lockCalls"),
                            ) {
                                s.set("lockCalls", it)
                            }
                            MediaAccess(s)
                            for (key in
                                listOf(
                                    "Calls and alarms",
                                    "Navigation and rides",
                                    "Timers and downloads",
                                    "Keep live page awake",
                                    "Sync dismissals",
                                    "Banners over rear apps",
                                )) DashToggle(
                                key,
                                s.flag(
                                    key,
                                    key != "Keep live page awake" &&
                                        key != "Banners over rear apps",
                                ),
                            ) {
                                s.set(key, it)
                                if (it &&
                                    key == "Banners over rear apps" &&
                                    !Settings.canDrawOverlays(context))
                                    context.startActivity(
                                        Intent(
                                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                            Uri.parse("package:" + context.packageName),
                                        ),
                                    )
                            }
                        }
                    }
                    LayerSettings.CONTROLS ->
                        Section("Pinned actions") {
                            OverlayToggle(s, Page.CONTROLS)
                            for (id in s.pins()) {
                                val action = s.action(id) ?: continue
                                Field(
                                    s,
                                    "label.$id",
                                    "Tile label",
                                    action.label.ifBlank { action.kind.title },
                                )
                                Options(
                                    "Icon",
                                    s.value("icon.$id", "Automatic"),
                                    listOf("Automatic", "Music", "Light", "Home", "Settings"),
                                ) {
                                    s.set("icon.$id", it)
                                }
                                if (lockEligible(action))
                                    DashToggle("Available while locked", s.flag("locked.$id")) {
                                        s.set("locked.$id", it)
                                    }
                                Row {
                                    TextButton({ picking = id }) { Text("Change") }
                                    TextButton({ s.save("tiles", shift(s.pins(), id, -1)) }) {
                                        Text("Earlier")
                                    }
                                    TextButton({ s.save("tiles", s.pins() - id) }) {
                                        Text("Remove")
                                    }
                                }
                            }
                            if (s.pins().size < 24)
                                Button({ picking = "tile." + java.util.UUID.randomUUID() }) {
                                    Text("Add action")
                                }
                        }
                    LayerSettings.APPS -> LauncherSettings(s)
                    LayerSettings.BACKGROUND -> {
                        BackgroundSettings(s, ::fileFor)
                        Section("Wallpaper gallery") {
                            SettingRow(
                                "Photos, GIFs and rotation",
                                onClick = {
                                    openLegacy(Feature.WALLPAPER, R.string.rear_edit_images)
                                },
                                icon = R.drawable.rear_ic_image)
                        }
                    }
                    LayerSettings.AMBIENT -> AmbientSettings(s)
                    LayerSettings.SHARED ->
                        Section("Share to the back") {
                            OverlayToggle(s, Page.SHARED)
                            Text(
                                "Share text or an image from any app to Pin to back screen. Crop photos before pinning, then open Pinned notes and photos on the back.",
                            )
                            DashToggle("Three-finger swipe up to crop", s.flag("threeFinger")) {
                                s.set("threeFinger", it)
                                io.github.xiaomibutton.remapper.runtime.ButtonAccessibilityService
                                    .connected
                                    ?.refreshFlags()
                                if (it &&
                                    io.github.xiaomibutton.remapper.runtime
                                        .ButtonAccessibilityService
                                        .connected == null)
                                    context.startActivity(
                                        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
                                    )
                            }
                            Text(
                                "Android touch exploration changes how taps and gestures work while this shortcut is on. You can also assign Crop front screen to back to the AI button.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Options(
                                "Note size",
                                s.value("noteSize", "Medium"),
                                listOf("Small", "Medium", "Large"),
                            ) {
                                s.set("noteSize", it)
                            }
                            TextButton(
                                {
                                    context.startActivity(
                                        Intent(context, PinActivity::class.java),
                                    )
                                },
                            ) {
                                Text("Write a note or choose a photo")
                            }
                        }
                    LayerSettings.CONNECTIONS -> {
                        Panel { OverlayToggle(s, Page.CONNECTIONS) }
                        ConnectionSettings(s, ::fileFor)
                    }
                    LayerSettings.PROFILES -> ProfileSettings(s)
                    LayerSettings.SHORTCUTS ->
                        Section("Overlay shortcuts") {
                            Text(
                                "Choose what opens from the overlay button. The wallpaper stays in view.")
                            Ordered(
                                s,
                                "pages",
                                Page.entries.map { it.name },
                                s.pages().map { it.name },
                            ) {
                                Page.valueOf(it).title
                            }
                        }
                }
        }
    }
    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Headline("Back screen")
                if (model != null) QuietText(model)
            }
            Text(
                "Preview · $previewMode",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            val preview: @Composable (Modifier) -> Unit = { bounds ->
                DashboardPreview(
                    s,
                    previewPage,
                    showAmbient,
                    showShortcuts,
                    chooseWallpaper = { openLegacy(Feature.WALLPAPER, R.string.rear_edit_images) },
                    modifier = bounds,
                )
            }
            if (maxWidth >= 500.dp && maxWidth > maxHeight) {
                val previewHeight = maxHeight
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    preview(Modifier.weight(1f, fill = false).heightIn(max = previewHeight))
                    SettingsSheet(
                        selected, Modifier.weight(1f).fillMaxHeight(), { group = null }, editor)
                }
            } else {
                val previewHeight = maxHeight * .49f
                Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                    preview(Modifier.widthIn(max = 430.dp).heightIn(max = previewHeight))
                    SettingsSheet(
                        selected,
                        Modifier.fillMaxWidth().weight(1f).offset(y = (-10).dp),
                        { group = null },
                        editor,
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsSheet(
    selected: LayerSettings?,
    modifier: Modifier,
    close: () -> Unit,
    editor: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier,
        shape = PanelShape,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.clipToBounds()) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = 14.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GroupTitle(selected?.title ?: "Rear display settings", Modifier.weight(1f))
                if (selected != null) {
                    IconButton(close) {
                        Icon(
                            androidx.compose.ui.res.painterResource(R.drawable.action_arrow_back),
                            contentDescription = "Back to rear display settings",
                        )
                    }
                }
            }
            key(selected) {
                Column(
                    Modifier.fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(start = 10.dp, end = 10.dp, bottom = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    content = editor,
                )
            }
        }
    }
}

@Composable
private fun DashboardSettingsList(
    s: DashboardStore,
    displayOn: Boolean,
    shizuku: ShizukuState,
    openLegacy: (Feature, Int) -> Unit,
    open: (LayerSettings) -> Unit,
) {
    val context = s.context
    val ready = shizuku == ShizukuState.READY
    val allowHandoffAlerts =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    fun askHandoffAlerts(feature: FeatureSettings) {
        if (listOf(CameraSettings, MirrorSettings).any { it.askedNotifications(context) }) return
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED)
            return
        feature.setAskedNotifications(context)
        allowHandoffAlerts.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    // Native editors write separate preference files. Read them again on returning from an editor.
    var native by remember { mutableStateOf(readNativeLayers(s)) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                native = readNativeLayers(s)
                delay(500)
            }
        }
    }
    if (!ready || !displayOn)
        Panel {
            SettingRow(
                if (ready) "Show wallpaper on rear display" else "Set up the rear display",
                if (ready) null else "Allow Shizuku access to use the rear display",
                onClick = {
                    if (ready) KeeperService.setWallpaper(context, true)
                    else openLegacy(Feature.WALLPAPER, 0)
                },
                icon = R.drawable.rear_ic_tile,
            )
        }
    GroupTitle("On the wallpaper")
    Panel {
        LayerRow(LayerSettings.BACKGROUND, s.value("scene", DashboardStore.WALLPAPER), open)
        RowDivider()
        FeatureRow(
            "Clock",
            LayerSettings.GLANCE.icon,
            s.glanceClock() || native.clock,
            { open(LayerSettings.GLANCE) }) { on ->
                s.set("Glance clock", on)
                if (!on) WallpaperSettings.setShowClock(context, false)
                RearHostActivity.settingsChanged()
                applyToWallpaper(context, on)
                native = readNativeLayers(s)
            }
        RowDivider()
        FeatureRow(
            "Notification banners",
            R.drawable.rear_ic_notifications,
            native.notifications,
            { openLegacy(Feature.NOTIFICATIONS, 0) }) { on ->
                NotificationSettings.setEnabled(context, on)
                RearNotificationListener.switched(context, on)
                RearHostActivity.notificationsChanged()
                if (on && !NotificationAccess.isGranted(context) && ready) {
                    NotificationAccess.grant(context) { granted ->
                        if (granted && NotificationSettings.isEnabled(context))
                            RearNotificationListener.switched(context, true)
                        else if (!granted && NotificationSettings.isEnabled(context))
                            openLegacy(Feature.NOTIFICATIONS, R.string.rear_edit_delivery)
                    }
                }
                applyToWallpaper(context, on)
                native = readNativeLayers(s)
            }
        RowDivider()
        FeatureRow(
            "Charging animation",
            R.drawable.rear_ic_battery_charging,
            native.charging,
            { openLegacy(Feature.BATTERY, 0) }) { on ->
                BatterySettings.setEnabled(context, on)
                applyToWallpaper(context, on && !BatterySettings.overXiaomi(context))
                KeeperService.start(context, KeeperService.ACTION_UPDATE)
                RearHostActivity.chargingChanged()
                native = readNativeLayers(s)
            }
        RowDivider()
        FeatureRow(
            "Lens rings",
            R.drawable.action_brightness_auto,
            s.flag("lensRings"),
            { open(LayerSettings.CONNECTIONS) }) { on ->
                s.set("lensRings", on)
                applyToWallpaper(context, on)
            }
    }
    GroupTitle("On demand")
    Panel {
        val settings =
            listOf(
                Page.HOME to LayerSettings.GLANCE,
                Page.MUSIC to LayerSettings.MUSIC,
                Page.LIVE to LayerSettings.LIVE,
                Page.INBOX to LayerSettings.LIVE,
                Page.CONTROLS to LayerSettings.CONTROLS,
                Page.APPS to LayerSettings.APPS,
                Page.SHARED to LayerSettings.SHARED,
                Page.CONNECTIONS to LayerSettings.CONNECTIONS,
            )
        for ((page, layer) in settings) {
            FeatureRow(page.title, page.icon, page in s.pages(), { open(layer) }) { on ->
                s.showPage(page, on)
                applyToWallpaper(context, on)
            }
            RowDivider()
        }
        FeatureRow(
            "Rear camera swipe",
            R.drawable.rear_ic_camera,
            native.camera,
            { openLegacy(Feature.CAMERA, 0) }) { on ->
                CameraSettings.setEnabled(context, on)
                RearHostActivity.swipesChanged()
                applyToWallpaper(context, on)
                if (on) askHandoffAlerts(CameraSettings)
                native = readNativeLayers(s)
            }
        RowDivider()
        FeatureRow(
            "Quick Switch tile",
            R.drawable.rear_ic_swap,
            native.mirror,
            { openLegacy(Feature.MIRROR, 0) }) { on ->
                MirrorSettings.setEnabled(context, on)
                KeeperService.start(context, KeeperService.ACTION_UPDATE)
                QuickSwitchTile.requestUpdate(context)
                if (on) askHandoffAlerts(MirrorSettings)
                native = readNativeLayers(s)
            }
        RowDivider()
        LayerRow(LayerSettings.SHORTCUTS, "Choose the order or add Tap garden", open)
    }
    GroupTitle("Display behavior")
    Panel {
        FeatureRow(
            "Always-on",
            LayerSettings.AMBIENT.icon,
            s.flag("Always-on"),
            { open(LayerSettings.AMBIENT) }) { on ->
                s.set("Always-on", on)
                applyToWallpaper(context, on)
            }
        RowDivider()
        FeatureRow(
            "Pocket mode",
            R.drawable.action_smartphone,
            s.pocketMode(),
            { open(LayerSettings.AMBIENT) }) { on ->
                s.set("Pocket mode", on)
                applyToWallpaper(context, on)
            }
        RowDivider()
        LayerRow(LayerSettings.PROFILES, null, open)
        RowDivider()
        SettingRow(
            "Display timing and setup",
            "Wake, schedules and recovery",
            { openLegacy(Feature.WALLPAPER, R.string.rear_edit_routine) },
            icon = R.drawable.action_settings)
        if (displayOn) {
            RowDivider()
            SettingRow(
                "Restore Xiaomi rear display",
                onClick = {
                    KeeperService.setWallpaper(context, false)
                },
                icon = R.drawable.action_replay)
        }
    }
}

private data class NativeLayers(
    val clock: Boolean,
    val notifications: Boolean,
    val charging: Boolean,
    val camera: Boolean,
    val mirror: Boolean,
)

private fun readNativeLayers(s: DashboardStore) =
    NativeLayers(
        WallpaperSettings.showClock(s.context),
        NotificationSettings.isEnabled(s.context),
        BatterySettings.isEnabled(s.context),
        CameraSettings.isEnabled(s.context),
        MirrorSettings.isEnabled(s.context),
    )

internal fun applyToWallpaper(context: android.content.Context, enabled: Boolean) {
    if (enabled &&
        !WallpaperSettings.isEnabled(context) &&
        ShizukuState.read(context) == ShizukuState.READY)
        KeeperService.setWallpaper(context, true)
}

@Composable
private fun FeatureRow(
    title: String,
    icon: Int,
    on: Boolean,
    settings: () -> Unit,
    change: (Boolean) -> Unit
) {
    SettingRow(
        title,
        onClick = settings,
        icon = icon,
        trailing = {
            Box(
                Modifier.minimumInteractiveComponentSize()
                    .toggleable(value = on, role = Role.Switch, onValueChange = change)
                    .semantics { contentDescription = title },
                contentAlignment = Alignment.Center,
            ) {
                Switch(checked = on, onCheckedChange = null)
            }
        },
    )
}

@Composable
private fun LayerRow(layer: LayerSettings, summary: String?, open: (LayerSettings) -> Unit) {
    SettingRow(layer.title, summary, { open(layer) }, icon = layer.icon)
}

@Composable
internal fun OverlayToggle(s: DashboardStore, page: Page) {
    DashToggle("Show ${page.title.lowercase()} in overlays", page in s.pages()) { on ->
        s.showPage(page, on)
        applyToWallpaper(s.context, on)
    }
}

@Composable
private fun MediaAccess(s: DashboardStore) {
    Button({ s.context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }) {
        Text("Grant media and card access")
    }
    Text(
        "Choose the remapper’s media session listener.", style = MaterialTheme.typography.bodySmall)
}

internal fun <T> shift(items: List<T>, item: T, by: Int): List<T> {
    val index = items.indexOf(item)
    if (index < 0 || index + by !in items.indices) return items
    return items.toMutableList().apply {
        removeAt(index)
        add(index + by, item)
    }
}

@Composable
private fun Ordered(
    s: DashboardStore,
    key: String,
    choices: List<String>,
    defaults: List<String>,
    label: (String) -> String,
) {
    val current = s.strings(key, defaults).filter { it in choices }.distinct()
    (current + choices).distinct().forEach { item ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                item in current,
                { checked ->
                    s.save(key, if (checked) current + item else current - item)
                    if (key == "pages") applyToWallpaper(s.context, checked)
                },
            )
            Text(label(item), Modifier.weight(1f))
            if (item in current)
                TextButton(
                    { s.save(key, shift(current, item, -1)) },
                    enabled = current.first() != item,
                ) {
                    Text("Earlier")
                }
        }
    }
}

@Composable
internal fun colorSetting(s: DashboardStore, key: String) {
    Options(
        key,
        s.value("color.$key", "Theme"),
        listOf("Theme", "Terracotta", "Apricot", "Sage", "Ivory", "Charcoal", "Custom"),
    ) {
        s.set("color.$key", it)
    }
    if (s.value("color.$key") == "Custom") Field(s, "hex.$key", "Hex color (#RRGGBB)", "#A64E32")
}

@Composable
internal fun ink(s: DashboardStore, key: String, fallback: Color): Color =
    when (s.value("color.$key")) {
        "Terracotta" -> Color(0xffc86746)
        "Apricot" -> Color(0xffffb38c)
        "Sage" -> Color(0xff799879)
        "Custom" ->
            runCatching { Color(android.graphics.Color.parseColor(s.value("hex.$key", "#A64E32"))) }
                .getOrDefault(fallback)
        "Ivory" -> Color(0xfffff3e5)
        "Charcoal" -> Color(0xff302a26)
        else -> fallback
    }
