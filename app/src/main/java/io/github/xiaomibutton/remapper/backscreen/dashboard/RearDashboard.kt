package io.github.xiaomibutton.remapper.backscreen.dashboard

import android.app.Activity
import android.content.Context
import android.media.*
import android.media.session.*
import android.os.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.*
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import io.github.xiaomibutton.remapper.*
import io.github.xiaomibutton.remapper.R
import kotlinx.coroutines.*

@Composable
internal fun RearDashboard(
    a: Activity,
    s: DashboardStore,
    page: Page,
    locked: Boolean,
    faceDown: Boolean,
    tilt: Float,
    open: (Page) -> Unit,
    close: () -> Unit,
    unlock: () -> Unit,
) {
    val now by
        produceState(System.currentTimeMillis()) {
            while (true) {
                value = System.currentTimeMillis()
                delay(1000)
            }
        }
    val scope = rememberCoroutineScope()
    var status by remember(page) { mutableStateOf("") }
    LaunchedEffect(status) {
        if (status.isNotEmpty()) {
            delay(4000)
            status = ""
        }
    }
    val rows = remember(now / 2000, locked) { if (locked) emptyList() else notices(s) }
    val pages = s.pages()
    val effective = if (page in pages) page else pages.firstOrNull()
    val secure = effective !in setOf(Page.HOME, Page.CONTROLS, Page.GAME)
    Box(Modifier.fillMaxSize()) {
        Scene(s, faceDown, tilt)
        Column(Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LazyRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(pages, key = { it.name }) { p ->
                        FilterChip(
                            selected = p == effective,
                            onClick = { open(p) },
                            leadingIcon =
                                if (p == effective)
                                    ({
                                        Icon(
                                            androidx.compose.ui.res.painterResource(p.icon),
                                            null,
                                            Modifier.size(16.dp),
                                        )
                                    })
                                else null,
                            label = { Text(p.title, maxLines = 1) },
                        )
                    }
                }
                IconButton(
                    close,
                    modifier = Modifier.semantics { contentDescription = "Close dashboard" },
                ) {
                    LineIcon(Mark.CLOSE)
                }
            }
            key(effective) {
                Column(
                    Modifier.fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(bottom = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (status.isNotEmpty())
                        Text(status, style = MaterialTheme.typography.labelLarge)
                    if (locked && effective == Page.LIVE && s.flag("lockCalls"))
                        LockedCallCards(s, now, unlock)
                    else if (secure && locked) {
                        Text("Unlock to view ${effective?.title.orEmpty()}")
                        Button(unlock) { Text("Unlock phone") }
                    } else
                        when (effective) {
                            null -> Text("Choose the pages to show in the front editor.")
                            Page.HOME ->
                                HomeCards(s, now, locked, open) { id ->
                                    scope.launch { status = resultMessage { runTile(s, id) } }
                                }
                            Page.MUSIC -> MusicPage(s, now)
                            Page.LIVE,
                            Page.INBOX ->
                                LivePage(s, rows, effective == Page.LIVE, now) { status = it }
                            Page.CONTROLS -> ControlsPage(a, s, locked, now) { status = it }
                            Page.APPS -> AppsPage(a, s) { status = it }
                            Page.SHARED -> SharedPage(s)
                            Page.CONNECTIONS -> ConnectionPage(s)
                            Page.GAME -> Garden(s)
                        }
                }
            }
        }
    }
}

internal suspend fun resultMessage(work: suspend () -> String): String =
    try {
        work()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        e.message ?: "Action unavailable"
    }

@Composable
internal fun <T> reading(key: Any?, refresh: Long = 60000, work: () -> T): State<Result<T>?> =
    produceState<Result<T>?>(null, key) {
        value = null
        while (true) {
            value = withContext(Dispatchers.IO) { runCatching(work) }
            delay(refresh)
        }
    }

@Composable
internal fun HomeCards(
    s: DashboardStore,
    now: Long,
    locked: Boolean,
    open: (Page) -> Unit,
    run: (String) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column {
            ClockFace(s, now, false)
            if (s.flag("Show date", true))
                Text(
                    android.text.format.DateFormat.format("EEEE, d MMMM", now).toString(),
                    style = MaterialTheme.typography.bodyMedium,
                )
        }
        if (s.flag("Battery percentage", true) && "Battery" !in s.cards())
            Text("${battery(s.context)}% charged", style = MaterialTheme.typography.labelLarge)
    }
    val compact = s.value("size", "Compact") == "Compact"
    val cards = s.cards().filter { !locked || it in setOf("Battery", "Weather", "Next alarm") }
    for (row in cards.chunked(if (compact) 2 else 1)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            for (card in row) key(card) {
                Box(Modifier.weight(1f)) { HomeCard(s, card, open, run) }
            }
            if (compact && row.size == 1) Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun HomeCard(s: DashboardStore, card: String, open: (Page) -> Unit, run: (String) -> Unit) {
    val label =
        when (card) {
            "Battery" -> "${battery(s.context)}%"
            "Next alarm" -> nextAlarm(s.context)
            "Audio output" -> headphones(s.context)?.productName?.toString() ?: "Phone speaker"
            "Calendar" -> {
                val item by reading(s.flag("eventTitles", true), 60000) { nextEvent(s) }
                item?.getOrNull() ?: "Calendar unavailable"
            }
            "Weather" -> {
                val w by reading(s.value("city") to s.value("units"), 900000) { weather(s) }
                w?.getOrNull()?.label ?: w?.exceptionOrNull()?.message ?: "Updating…"
            }
            "Now playing" ->
                player(s)?.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
                    ?: "Nothing playing"
            "Accessory battery" -> AccessoryBattery(s)
            else -> s.value("feedLabel", "Connected cards")
        }
    Surface(
        color =
            MaterialTheme.colorScheme.surfaceContainer.copy(
                alpha = if (s.value("finish") == "Glass") .66f else 1f,
            ),
        shape = TileShape,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier =
            Modifier.fillMaxWidth()
                .combinedClickable(
                    onClick = {
                        if (s.action("tap.$card") != null) run("tap.$card")
                        else if (card == "Now playing") open(Page.MUSIC)
                        else if (card == "Connected cards") open(Page.CONNECTIONS)
                    },
                    onLongClick = { if (s.action("hold.$card") != null) run("hold.$card") },
                ),
    ) {
        Column(Modifier.padding(if (s.value("size") == "Expanded") 16.dp else 9.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val icon =
                    when (card) {
                        "Battery",
                        "Accessory battery" -> R.drawable.action_battery_saver
                        "Weather" -> R.drawable.action_brightness_auto
                        "Calendar",
                        "Next alarm" -> R.drawable.action_alarm
                        "Audio output" -> R.drawable.action_volume_up
                        "Now playing" -> R.drawable.action_music_note
                        else -> R.drawable.action_link
                    }
                Icon(
                    androidx.compose.ui.res.painterResource(icon),
                    null,
                    Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    card,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(label, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun AccessoryBattery(s: DashboardStore): String {
    var level by remember { mutableIntStateOf(-1) }
    DisposableEffect(s) {
        val receiver =
            object : android.content.BroadcastReceiver() {
                override fun onReceive(c: Context?, i: android.content.Intent?) {
                    level = i?.getIntExtra("android.bluetooth.device.extra.BATTERY_LEVEL", -1) ?: -1
                }
            }
        val filter =
            android.content.IntentFilter("android.bluetooth.device.action.BATTERY_LEVEL_CHANGED")
        androidx.core.content.ContextCompat.registerReceiver(
            s.context,
            receiver,
            filter,
            androidx.core.content.ContextCompat.RECEIVER_EXPORTED,
        )
        onDispose { s.context.unregisterReceiver(receiver) }
    }
    return if (level in 0..100 && headphones(s.context) != null) "$level%" else "No battery reading"
}

@Composable
internal fun AppsPage(a: Activity, s: DashboardStore, message: (String) -> Unit) {
    val apps = s.strings("apps")
    val scope = rememberCoroutineScope()
    if (apps.isEmpty()) {
        Text("Pin apps in Launcher and keyboard on the front.")
        return
    }
    val columns = s.value("columns", "3").toIntOrNull()?.coerceIn(2, 4) ?: 3
    @Composable
    fun app(pkg: String, modifier: Modifier) {
        val icon =
            remember(pkg) {
                runCatching {
                    s.context.packageManager
                        .getApplicationIcon(pkg)
                        .toBitmap(72, 72)
                        .asImageBitmap()
                }
                    .getOrNull()
            }
        val label = remember(pkg) { appLabel(s, pkg) }
        Surface(
            onClick = {
                scope.launch {
                    val result = resultMessage {
                        openRearApp(a, s, pkg)
                        ""
                    }
                    if (result.isNotEmpty()) message(result)
                }
            },
            shape = TileShape,
            color =
                MaterialTheme.colorScheme.surfaceContainer.copy(
                    alpha = if (s.value("finish") == "Glass") .66f else 1f,
                ),
            modifier = modifier,
        ) {
            Column(
                Modifier.padding(6.dp).heightIn(min = 48.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                icon?.let { Image(it, label, Modifier.size(36.dp)) }
                if (s.flag("appLabels", true))
                    Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
            }
        }
    }
    if (s.value("launcher") == "Carousel")
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(apps) { app(it, Modifier.width(90.dp)) }
        }
    else
        for (row in apps.chunked(columns)) Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            row.forEach { app(it, Modifier.weight(1f)) }
            repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
        }
}

@Composable
private fun ConnectionPage(s: DashboardStore) {
    var version by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    var result by remember { mutableStateOf("") }
    val marker = remember(version) { System.currentTimeMillis() }
    if (s.value("haBase").isNotBlank()) {
        val current by reading(version) { entity(s) }
        Section("Home Assistant") {
            Text(current?.fold({ it }, { it.message ?: "Unavailable" }) ?: "Connecting…")
            Button(
                {
                    scope.launch {
                        result = resultMessage { withContext(Dispatchers.IO) { entity(s, true) } }
                        version++
                    }
                },
            ) {
                Text("Run action")
            }
        }
    }
    if (s.value("feed").isNotBlank()) {
        val current by
            reading(version) {
                feedValue(webJson(s.value("feed")), s.value("path", "status")) to
                    System.currentTimeMillis()
            }
        Section(s.value("feedLabel", "Status")) {
            Text(
                current?.getOrNull()?.first ?: current?.exceptionOrNull()?.message ?: "Refreshing…",
            )
            current?.getOrNull()?.let {
                Text(
                    "Updated " + android.text.format.DateFormat.format("HH:mm", it.second),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
    for (i in 1..2) if (
        s.value("ring$i", "Session") != "Hidden" &&
            (s.value("usageFile$i").isNotBlank() ||
                s.value("usageUrl$i").isNotBlank() ||
                s.value("ring$i") == "Battery")
    ) {
        val reading by
            reading(version to i) {
                if (s.value("ring$i") == "Battery") RingReading(battery(s.context).toFloat(), null)
                else usage(s, i)
            }
        Section(s.value("account$i", "Account $i")) {
            val value = reading?.getOrNull()
            if (value == null) Text(reading?.exceptionOrNull()?.message ?: "Reading…")
            else {
                val remaining =
                    ((value.reset ?: marker) - System.currentTimeMillis()).coerceAtLeast(0)
                val reset = s.value("metric$i") == "Until reset" && value.reset != null
                val span =
                    if (s.value("ring$i", "Session") == "Session") 5 * 3600000L else 7 * 86400000L
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CircularProgressIndicator(
                        progress = {
                            if (reset) (remaining.toFloat() / span).coerceIn(0f, 1f)
                            else value.percent / 100f
                        },
                        color = ink(s, "Ring $i", MaterialTheme.colorScheme.primary),
                    )
                    if (s.flag("companion$i", true)) Text(if (value.percent < 75) "✿" else "☀")
                    Text(
                        if (reset) "${remaining/60000} min to reset"
                        else "${value.percent.toInt()}%",
                    )
                }
            }
        }
    }
    if (
        s.value("haBase").isBlank() &&
            s.value("feed").isBlank() &&
            (1..2).none {
                s.value("usageFile$it").isNotBlank() ||
                    s.value("usageUrl$it").isNotBlank() ||
                    s.value("ring$it") == "Battery"
            }
    )
        Text("Add a Home Assistant entity, JSON feed or usage source in Connections.")
    if (result.isNotBlank()) Text(result)
    TextButton({ version++ }) { Text("Refresh") }
}

@Composable
private fun Garden(s: DashboardStore) {
    var target by remember { mutableIntStateOf(kotlin.random.Random.nextInt(6)) }
    var score by remember { mutableIntStateOf(0) }
    val best = s.value("gardenBest", "0").toIntOrNull() ?: 0
    Text("Tap the flower · $score   Best $best", style = MaterialTheme.typography.titleSmall)
    repeat(2) { row ->
        Row {
            repeat(3) { column ->
                val cell = row * 3 + column
                FilledTonalButton(
                    {
                        if (cell == target) {
                            score++
                            if (score > best) s.set("gardenBest", "$score")
                            target = (target + 1 + kotlin.random.Random.nextInt(5)) % 6
                        } else score = 0
                    },
                    Modifier.weight(1f),
                ) {
                    Text(if (cell == target) "✿" else "·")
                }
            }
        }
    }
}
