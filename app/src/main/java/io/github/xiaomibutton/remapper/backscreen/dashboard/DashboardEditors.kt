package io.github.xiaomibutton.remapper.backscreen.dashboard

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun LauncherSettings(s: DashboardStore) {
    var picker by remember { mutableStateOf(false) }
    val apps = s.strings("apps")
    Section("Pinned apps") {
        OverlayToggle(s, Page.APPS)
        Button({ picker = true }) { Text("Choose pinned apps") }
        if (apps.isNotEmpty()) {
            DashToggle("Swipe up from the wallpaper to open apps", s.flag("edgeLauncher", true)) {
                s.set("edgeLauncher", it)
            }
        }
    }
    Section("Layout and keyboard") {
        Options("Layout", s.value("launcher", "Grid"), listOf("Grid", "Carousel")) {
            s.set("launcher", it)
        }
        Options("Columns", s.value("columns", "3"), listOf("2", "3", "4")) { s.set("columns", it) }
        DashToggle("Show application names", s.flag("appLabels", true)) { s.set("appLabels", it) }
        DashToggle("Open keyboard on the back", s.flag("rearIme", true)) { s.set("rearIme", it) }
        DashToggle("Compact rear keyboard", s.flag("compactKeyboard")) { on ->
            s.set("compactKeyboard", on)
            if (on &&
                io.github.xiaomibutton.remapper.runtime.ButtonAccessibilityService.connected ==
                    null)
                s.context.startActivity(
                    Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS),
                )
        }
        Text(
            "Use the compact layout if this phone sends its system keyboard to the front.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
    apps.forEach { pkg ->
        Section(appLabel(s, pkg)) {
            Options(
                "Density",
                s.value("dpi.$pkg", "System"),
                listOf("System", "160", "200", "240", "280", "320", "360"),
            ) {
                s.set("dpi.$pkg", it)
            }
            Options(
                "Window width",
                s.value("width.$pkg", "Full"),
                listOf("Full", "Camera safe", "70%", "85%"),
            ) {
                s.set("width.$pkg", it)
            }
            Row {
                TextButton({ s.save("apps", shift(apps, pkg, -1)) }) { Text("Earlier") }
                TextButton({ s.save("apps", apps - pkg) }) { Text("Unpin") }
            }
        }
    }
    if (picker) {
        var query by remember { mutableStateOf("") }
        val choices by
            produceState(emptyList<Pair<String, String>>()) {
                value =
                    withContext(Dispatchers.IO) {
                        val pm = s.context.packageManager
                        pm.queryIntentActivities(
                                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
                                0,
                            )
                            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
                            .filter { it.first != s.context.packageName }
                            .distinctBy { it.first }
                            .sortedBy { it.second.lowercase() }
                    }
            }
        AlertDialog(
            onDismissRequest = { picker = false },
            title = { Text("Pin apps") },
            text = {
                Column {
                    OutlinedTextField(
                        query,
                        { query = it },
                        label = { Text("Search apps") },
                        singleLine = true,
                    )
                    LazyColumn(Modifier.heightIn(max = 340.dp)) {
                        items(
                            choices.filter { it.second.contains(query, true) },
                            key = { it.first },
                        ) { (pkg, label) ->
                            DashToggle(label, pkg in apps) { pin ->
                                if (!pin || apps.size < 48)
                                    s.save("apps", if (pin) apps + pkg else apps - pkg)
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton({ picker = false }) { Text("Done") } },
        )
    }
}

internal fun appLabel(s: DashboardStore, pkg: String) = runCatching {
    val pm = s.context.packageManager
    pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
}
    .getOrDefault(pkg)

@Composable
internal fun GlanceSettings(s: DashboardStore, file: (String, String) -> Unit) {
    Section("Clock on the wallpaper") {
        DashToggle("Show glance clock", s.glanceClock()) { on ->
            s.set("Glance clock", on)
            applyToWallpaper(s.context, on)
        }
        if (!s.glanceClock()) return@Section
        Options("Face", s.value("clockFace", "Digital"), listOf("Digital", "Analog")) {
            s.set("clockFace", it)
        }
        Options(
            "Time format",
            s.value("format", "System"),
            listOf("System", "12 hour", "24 hour"),
        ) {
            s.set("format", it)
        }
        for (key in listOf("Seconds", "Show date", "Battery percentage")) DashToggle(
            key, s.flag(key, key == "Show date" || key == "Battery percentage")) {
            s.set(key, it)
        }
        Button({ file("font", "*/*") }) { Text("Import a font") }
        if (s.value("font").isNotEmpty())
            TextButton({ s.set("font", "") }) { Text("Use system font") }
        colorSetting(s, "Clock")
    }
}

@Composable
internal fun BackgroundSettings(s: DashboardStore, file: (String, String) -> Unit) {
    Section("Background source") {
        val scene = s.value("scene", DashboardStore.WALLPAPER)
        Options(
            "Wallpaper",
            scene,
            listOf(
                DashboardStore.WALLPAPER,
                "Warm",
                "Dark",
                "Solid color",
                "Silk",
                "Clay waves",
                "Soft metal",
                "Photo",
                "Video"),
        ) {
            s.set("scene", it)
            if (it != "Photo" && it != "Video") applyToWallpaper(s.context, true)
        }
        if (scene == "Solid color") colorSetting(s, "Background")
        if (scene in setOf("Silk", "Clay waves", "Soft metal")) {
            Options(
                "Animation speed",
                s.value("sceneSpeed", "Medium"),
                listOf("Slow", "Medium", "Fast")) {
                    s.set("sceneSpeed", it)
                }
            colorSetting(s, "Live wallpaper color")
        }
        if (scene == "Photo" || scene == "Video") {
            Button({ file("backdrop", if (scene == "Video") "video/*" else "image/*") }) {
                Text(if (scene == "Video") "Choose video" else "Choose photo")
            }
            Options("Framing", s.value("framing", "Crop"), listOf("Crop", "Fit")) {
                s.set("framing", it)
            }
        }
        if (scene == "Video") {
            Options(
                "Video playback",
                s.value("playback", "Loop"),
                listOf("Loop", "Once", "Ping-pong", "Reverse")) {
                    s.set("playback", it)
                }
            Field(s, "clipStart", "Clip start in seconds", "0")
            Field(s, "clipEnd", "Clip end in seconds")
        }
    }
    Section("Effects") {
        Options(
            "Weather effect",
            s.value("effect", "Off"),
            listOf("Off", "Automatic", "Rain", "Snow", "Storm")) {
                s.set("effect", it)
            }
        for (effect in listOf("Table lights", "Tilt parallax", "CRT lines")) DashToggle(
            effect, s.flag(effect)) {
                s.set(effect, it)
            }
        Options("Card finish", s.value("finish", "Flat"), listOf("Flat", "Glass")) {
            s.set("finish", it)
        }
    }
}

@Composable
internal fun AmbientSettings(s: DashboardStore) {
    Section("Display behavior") {
        DashToggle("Pocket mode", s.pocketMode()) { on ->
            s.set("Pocket mode", on)
            applyToWallpaper(s.context, on)
        }
        DashToggle("Always-on", s.flag("Always-on")) { on ->
            s.set("Always-on", on)
            applyToWallpaper(s.context, on)
        }
        Options("Idle timeout (seconds)", s.value("idle", "30"), listOf("10", "30", "60", "120")) {
            s.set("idle", it)
        }
        if (!s.flag("Always-on")) return@Section
        for (key in listOf("AOD date", "AOD battery", "AOD weather", "AOD badges", "Edge glow")) {
            DashToggle(key, s.flag(key, key == "AOD date")) {
                s.set(key, it)
            }
        }
        Options("Dim face", s.value("dimFace", "Digital"), listOf("Digital", "Analog")) {
            s.set("dimFace", it)
        }
    }
}

@Composable
internal fun ConnectionSettings(s: DashboardStore, file: (String, String) -> Unit) {
    val location =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
            s.set("deviceWeather", ok)
        }
    Section("Weather") {
        DashToggle("Use approximate phone location", s.flag("deviceWeather")) { on ->
            if (on) location.launch(android.Manifest.permission.ACCESS_COARSE_LOCATION)
            else s.set("deviceWeather", false)
        }
        Field(s, "city", "City or town")
        Options("Temperature", s.value("units", "°C"), listOf("°C", "°F")) { s.set("units", it) }
        Text(
            "Open-Meteo uses your chosen place to retrieve current conditions.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
    Section("Home Assistant") {
        Field(s, "haBase", "HTTPS server address")
        Field(s, "entity", "Entity ID", "light.living_room")
        var token by remember { mutableStateOf("") }
        OutlinedTextField(
            token,
            { token = it },
            singleLine = true,
            label = { Text("Access token") },
            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
        )
        Row {
            Button(
                {
                    Secrets.save(s.context, token)
                    token = ""
                },
                enabled = token.isNotBlank(),
            ) {
                Text("Save token")
            }
            TextButton(
                {
                    Secrets.clear(s.context)
                    token = ""
                },
            ) {
                Text("Forget token")
            }
        }
        var choose by remember { mutableStateOf(false) }
        TextButton({ choose = true }) { Text("Browse entities") }
        if (choose) {
            val entities by reading(s.value("haBase") to s.value("credential")) { homeEntities(s) }
            var search by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { choose = false },
                title = { Text("Choose an entity") },
                text = {
                    Column {
                        OutlinedTextField(
                            search,
                            { search = it },
                            label = { Text("Search entities") },
                            singleLine = true,
                        )
                        if (entities?.isSuccess != true)
                            Text(entities?.exceptionOrNull()?.message ?: "Loading entities…")
                        LazyColumn(Modifier.heightIn(max = 300.dp)) {
                            items(
                                entities?.getOrNull().orEmpty().filter {
                                    it.contains(search, true)
                                },
                            ) { id ->
                                TextButton(
                                    {
                                        s.set("entity", id)
                                        choose = false
                                    },
                                ) {
                                    Text(id)
                                }
                            }
                        }
                    }
                },
                confirmButton = { TextButton({ choose = false }) { Text("Close") } },
            )
        }
        Field(s, "service", "Tap action (domain.service)", "homeassistant.toggle")
    }
    Section("Custom JSON") {
        Field(s, "feed", "HTTPS feed URL")
        Field(s, "path", "Value path", "status")
        Field(s, "feedLabel", "Card label", "Status")
    }
    Section("Claude usage rings") {
        DashToggle("Draw rings around the camera lenses", s.flag("lensRings")) {
            s.set("lensRings", it)
        }
        if (s.flag("lensRings")) {
            DashToggle("Show rings on wallpaper", s.flag("wallpaperRings", true)) {
                s.set("wallpaperRings", it)
            }
            DashToggle("Animate companions", s.flag("companionMotion", true)) {
                s.set("companionMotion", it)
            }
            Field(s, "lensX", "Lens center from left (%)", "16")
            Field(s, "lensY1", "Top lens center (%)", "25")
            Field(s, "lensY2", "Bottom lens center (%)", "75")
            Field(s, "lensRadius", "Radius (% of panel height)", "20")
        }
        Text(
            "Choose a status-line JSON file or an HTTPS feed for each account. The rear reads its rate_limits fields.",
        )
        for (i in 1..2) {
            Field(s, "account$i", "Account $i label", "Account $i")
            Field(s, "usageUrl$i", "Account $i feed URL")
            TextButton({ file("usageFile$i", "*/*") }) { Text("Choose account $i JSON file") }
            if (s.value("usageFile$i").isNotEmpty())
                TextButton({ s.set("usageFile$i", "") }) { Text("Remove file") }
            Options(
                "Ring $i",
                s.value("ring$i", "Session"),
                listOf("Session", "Weekly", "Scoped weekly", "Battery", "Hidden"),
            ) {
                s.set("ring$i", it)
            }
            Options("Metric $i", s.value("metric$i", "Usage"), listOf("Usage", "Until reset")) {
                s.set("metric$i", it)
            }
            colorSetting(s, "Ring $i")
            DashToggle("Show companion $i", s.flag("companion$i", true)) {
                s.set("companion$i", it)
            }
            if (s.flag("companion$i", true))
                Options(
                    "Companion $i",
                    s.value("mascot$i", if (i == 1) "Sprout" else "Firefly"),
                    companionStyles,
                ) {
                    s.set("mascot$i", it)
                }
        }
    }
}

@Composable
internal fun ProfileSettings(s: DashboardStore) {
    for (name in listOf("Desk", "Headphones", "Night")) Section(name) {
        DashToggle("Enable $name", s.flag("profile.$name")) { s.set("profile.$name", it) }
        val trigger =
            when (name) {
                "Desk" -> "Charging"
                "Night" -> "Night hours"
                else -> "Headphones"
            }
        Options(
            "Activate on",
            s.value("trigger.$name", trigger),
            listOf("Charging", "Headphones", "Night hours", "Media playing", "Locked"),
        ) {
            s.set("trigger.$name", it)
        }
        val available = s.pages().filter { it != Page.APPS }
        val destination = available.find { it.name == s.value("page.$name") }?.title ?: "Wallpaper"
        Options("On activation", destination, listOf("Wallpaper") + available.map { it.title }) {
            s.set("page.$name", available.find { page -> page.title == it }?.name.orEmpty())
        }
        Options(
            "Brightness",
            s.value("brightness.$name", "System"),
            listOf("System", "10%", "30%", "60%", "100%"),
        ) {
            s.set("brightness.$name", it)
        }
        Options("Idle seconds", s.value("timeout.$name", "30"), listOf("10", "30", "60", "120")) {
            s.set("timeout.$name", it)
        }
    }
}
