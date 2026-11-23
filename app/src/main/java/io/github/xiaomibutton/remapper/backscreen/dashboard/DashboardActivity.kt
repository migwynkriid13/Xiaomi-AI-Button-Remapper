package io.github.xiaomibutton.remapper.backscreen.dashboard

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import io.github.xiaomibutton.remapper.*
import io.github.xiaomibutton.remapper.R
import io.github.xiaomibutton.remapper.backscreen.core.KeeperService

class DashboardActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val store = DashboardStore(this)
        setContent {
            RemapperTheme {
                Surface(color = MaterialTheme.colorScheme.background) { Editor(store, ::finish) }
            }
        }
    }
}

@Composable
internal fun DashToggle(title: String, on: Boolean, change: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable { change(!on) },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(title, Modifier.weight(1f))
        Switch(checked = on, onCheckedChange = change)
    }
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
private fun Editor(s: DashboardStore, done: () -> Unit) {
    val context = s.context
    val groups =
        listOf(
            "Home and pages",
            "Music and live activities",
            "Control Centre",
            "Launcher and keyboard",
            "Always-on and appearance",
            "Pinned notes and photos",
            "Connections",
            "Profiles",
        )
    var group by rememberSaveable { mutableStateOf("") }
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
                    .onSuccess { s.set(fileKey, uri.toString()) }
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
    BackHandler(group.isNotEmpty()) { group = "" }
    Column(Modifier.fillMaxSize().systemBarsPadding()) {
        TextButton(onClick = { if (group.isEmpty()) done() else group = "" }) {
            Text("‹  " + if (group.isEmpty()) "Back screen" else "Rear dashboard")
        }
        key(group) {
            Column(
                Modifier.fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    group.ifEmpty { "Rear dashboard" },
                    style = MaterialTheme.typography.headlineLarge,
                )
                if (group.isEmpty()) {
                    Box(
                        Modifier.fillMaxWidth()
                            .height(144.dp)
                            .background(Color(0xff351f19), PanelShape),
                    ) {
                        androidx.compose.foundation.Image(
                            painterResource(R.drawable.back_screen_hero),
                            null,
                            Modifier.fillMaxSize().clip(PanelShape),
                            contentScale = ContentScale.Crop,
                        )
                    }
                    Section("On your phone") {
                        DashToggle("Show dashboard on the wallpaper", s.flag("enabled")) { enabled
                            ->
                            s.set("enabled", enabled)
                            runCatching {
                                KeeperService.start(
                                    context,
                                    if (enabled) KeeperService.ACTION_APPLY
                                    else KeeperService.ACTION_UPDATE,
                                )
                            }
                                .onFailure {
                                    android.widget.Toast.makeText(
                                            context,
                                            "Unable to start the back screen",
                                            android.widget.Toast.LENGTH_LONG,
                                        )
                                        .show()
                                }
                        }
                        val authorized by
                            produceState(false) {
                                while (true) {
                                    value =
                                        runCatching {
                                                rikka.shizuku.Shizuku.pingBinder() &&
                                                    rikka.shizuku.Shizuku.checkSelfPermission() ==
                                                        android.content.pm.PackageManager
                                                            .PERMISSION_GRANTED
                                            }
                                            .getOrDefault(false)
                                    kotlinx.coroutines.delay(2000)
                                }
                            }
                        if (authorized)
                            Text(
                                "Shizuku connected",
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.labelLarge,
                            )
                        else
                            TextButton(
                                {
                                    runCatching { rikka.shizuku.Shizuku.requestPermission(260) }
                                        .onFailure {
                                            android.widget.Toast.makeText(
                                                    context,
                                                    "Start Shizuku, then try again",
                                                    android.widget.Toast.LENGTH_LONG,
                                                )
                                                .show()
                                        }
                                },
                            ) {
                                Text("Grant Shizuku access")
                            }
                        Text(
                            "Tap Pages on the rear wallpaper to open your dashboard.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        TextButton(onClick = { group = "Preview" }) {
                            Text("Preview the rear display")
                        }
                    }
                    val glyphs =
                        listOf(
                            R.drawable.action_home,
                            R.drawable.action_music_note,
                            R.drawable.action_tune,
                            R.drawable.action_apps,
                            R.drawable.action_brightness_auto,
                            R.drawable.action_open_with,
                            R.drawable.action_link,
                            R.drawable.action_alarm,
                        )
                    groups.forEachIndexed { index, name ->
                        Surface(
                            onClick = { group = name },
                            shape = TileShape,
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        ) {
                            Row(
                                Modifier.fillMaxWidth().padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                ActionGlyph(glyphs[index])
                                Text(
                                    name,
                                    Modifier.weight(1f),
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                LineIcon(Mark.NEXT, Modifier.size(16.dp))
                            }
                        }
                    }
                } else
                    when (group) {
                        "Preview" -> DashboardPreview(s)
                        groups[0] -> {
                            Section("Pages") {
                                Ordered(
                                    s,
                                    "pages",
                                    Page.entries.map { it.name },
                                    Page.entries.map { it.name },
                                ) {
                                    Page.valueOf(it).title
                                }
                            }
                            Section("Home cards") {
                                Ordered(
                                    s,
                                    "cards",
                                    DashboardStore.CARD_NAMES,
                                    DashboardStore.CARD_NAMES.take(4),
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
                        groups[1] -> {
                            Section("Player") {
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
                            Section("Live activities and inbox") {
                                DashToggle(
                                    "Allow call and alarm sliders while locked",
                                    s.flag("lockCalls"),
                                ) {
                                    s.set("lockCalls", it)
                                }
                                Button(
                                    {
                                        context.startActivity(
                                            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS),
                                        )
                                    },
                                ) {
                                    Text("Grant media and card access")
                                }
                                Text(
                                    "Choose the remapper’s media session listener.",
                                    style = MaterialTheme.typography.bodySmall,
                                )
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
                                    if (
                                        it &&
                                            key == "Banners over rear apps" &&
                                            !Settings.canDrawOverlays(context)
                                    )
                                        context.startActivity(
                                            Intent(
                                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                                Uri.parse("package:" + context.packageName),
                                            ),
                                        )
                                }
                            }
                        }
                        groups[2] ->
                            Section("Pinned actions") {
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
                        groups[3] -> LauncherSettings(s)
                        groups[4] -> AppearanceSettings(s, ::fileFor)
                        groups[5] ->
                            Section("Share to the back") {
                                Text(
                                    "Share text or an image from any app to Pin to back screen. Crop photos before pinning, then open Pinned notes and photos on the back.",
                                )
                                DashToggle("Three-finger swipe up to crop", s.flag("threeFinger")) {
                                    s.set("threeFinger", it)
                                    io.github.xiaomibutton.remapper.runtime
                                        .ButtonAccessibilityService
                                        .connected
                                        ?.refreshFlags()
                                    if (
                                        it &&
                                            io.github.xiaomibutton.remapper.runtime
                                                .ButtonAccessibilityService
                                                .connected == null
                                    )
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
                        groups[6] -> ConnectionSettings(s, ::fileFor)
                        groups[7] -> ProfileSettings(s)
                    }
                Spacer(Modifier.height(20.dp))
            }
        }
    }
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
    val current = s.strings(key, defaults)
    (current + choices).distinct().forEach { item ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                item in current,
                { checked -> s.save(key, if (checked) current + item else current - item) },
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
