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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import io.github.xiaomibutton.remapper.*
import io.github.xiaomibutton.remapper.R
import kotlinx.coroutines.*

/** The same sheet is drawn over the wallpaper on the device and in the front preview. */
@Composable
internal fun RearOverlaySheet(
    title: String,
    close: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        val sheetHeight = maxHeight * .88f
        Box(
            Modifier.matchParentSize()
                .clickable(
                    interactionSource = null,
                    indication = null,
                    onClick = close,
                )
                .clearAndSetSemantics {},
        )
        Surface(
            modifier =
                Modifier.widthIn(max = 440.dp)
                    .fillMaxWidth()
                    .heightIn(max = sheetHeight)
                    .padding(horizontal = 8.dp),
            shape = PanelShape,
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Column {
                Row(
                    Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        title,
                        Modifier.weight(1f).semantics { heading() },
                        style = MaterialTheme.typography.titleMedium)
                    IconButton(close, Modifier.semantics { contentDescription = "Close overlay" }) {
                        LineIcon(Mark.CLOSE, Modifier.size(18.dp))
                    }
                }
                Column(
                    Modifier.weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                        .padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    content = content,
                )
            }
        }
    }
}

@Composable
internal fun OverlayShortcut(open: () -> Unit, modifier: Modifier = Modifier) {
    FilledIconButton(
        open,
        modifier.size(48.dp).semantics { contentDescription = "Open overlays" },
        colors =
            IconButtonDefaults.filledIconButtonColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ),
    ) {
        Icon(
            painterResource(R.drawable.action_tune),
            contentDescription = null,
            Modifier.size(20.dp))
    }
}

@Composable
internal fun OverlayTray(
    s: DashboardStore,
    locked: Boolean,
    open: (Page) -> Unit,
    close: () -> Unit
) {
    RearOverlaySheet("Overlays", close) {
        val shortcuts = s.pages().filter { !locked || it.availableWhenLocked(s) }
        if (shortcuts.isEmpty()) Text("No overlays available.")
        shortcuts.forEach { page ->
            Surface(
                onClick = { open(page) },
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                shape = TileShape,
            ) {
                Row(
                    Modifier.fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(9.dp),
                ) {
                    Icon(
                        painterResource(page.icon),
                        null,
                        Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.primary)
                    Text(
                        page.title,
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.titleSmall)
                    LineIcon(Mark.NEXT, Modifier.size(14.dp))
                }
            }
        }
    }
}

internal fun Page.availableWhenLocked(s: DashboardStore) =
    when (this) {
        Page.HOME,
        Page.CONTROLS,
        Page.GAME -> true
        Page.LIVE -> s.flag("lockCalls")
        else -> false
    }

@Composable
internal fun GlanceFace(s: DashboardStore, modifier: Modifier = Modifier) {
    val now by
        produceState(System.currentTimeMillis(), s.flag("Seconds")) {
            while (true) {
                value = System.currentTimeMillis()
                val interval = if (s.flag("Seconds")) 1_000L else 60_000L
                delay(interval - value % interval)
            }
        }
    Surface(
        modifier,
        shape = PanelShape,
        color = Color(0xB5251B17),
        contentColor = Color(0xFFFFF1E6),
    ) {
        Column(
            Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ClockFace(s, now, false, LocalContentColor.current)
            val details = buildList {
                if (s.flag("Show date", true))
                    add(android.text.format.DateFormat.format("EEE, d MMM", now).toString())
                if (s.flag("Battery percentage", true)) add("${battery(s.context)}%")
            }
            if (details.isNotEmpty())
                Text(details.joinToString(" · "), style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
internal fun NowPlayingGlance(s: DashboardStore, open: () -> Unit, modifier: Modifier = Modifier) {
    if (Page.MUSIC !in s.pages()) return
    val controller = player(s)
    val title by
        reading(controller, 2_000) {
            controller
                ?.takeIf { it.playbackState?.state == PlaybackState.STATE_PLAYING }
                ?.metadata
                ?.getString(MediaMetadata.METADATA_KEY_TITLE)
        }
    val track = title?.getOrNull()?.takeIf(String::isNotBlank) ?: return
    FilledTonalButton(
        open, modifier.heightIn(min = 48.dp), contentPadding = PaddingValues(horizontal = 10.dp)) {
            Icon(painterResource(Page.MUSIC.icon), contentDescription = null, Modifier.size(17.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                track,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
}

@Composable
internal fun RearDashboard(
    a: Activity,
    s: DashboardStore,
    page: Page,
    locked: Boolean,
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
    val rows =
        remember(now / 2000, locked, page) {
            if (!locked && (page == Page.LIVE || page == Page.INBOX)) notices(s) else emptyList()
        }
    key(page) {
        RearOverlaySheet(page.title, close) {
            if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.labelLarge)
            if (locked && page == Page.LIVE && s.flag("lockCalls")) LockedCallCards(s, now, unlock)
            else if (locked && !page.availableWhenLocked(s)) {
                Text("Unlock to view ${page.title.lowercase()}")
                Button(unlock) { Text("Unlock phone") }
            } else
                when (page) {
                    Page.HOME ->
                        HomeCards(s, locked, open) { id ->
                            scope.launch { status = resultMessage { runTile(s, id) } }
                        }
                    Page.MUSIC -> MusicPage(s, now)
                    Page.LIVE,
                    Page.INBOX -> LivePage(s, rows, page == Page.LIVE, now) { status = it }
                    Page.CONTROLS -> ControlsPage(a, s, locked, now) { status = it }
                    Page.APPS -> AppsPage(a, s, close) { status = it }
                    Page.SHARED -> SharedPage(s)
                    Page.CONNECTIONS -> ConnectionPage(s)
                    Page.GAME -> Garden(s)
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
private fun HomeCards(
    s: DashboardStore,
    locked: Boolean,
    open: (Page) -> Unit,
    run: (String) -> Unit,
) {
    val compact = s.value("size", "Compact") == "Compact"
    val cards = s.cards().filter { !locked || it in setOf("Battery", "Weather", "Next alarm") }
    if (cards.isEmpty()) Text("No glance cards to show.")
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
private fun AppsPage(a: Activity, s: DashboardStore, close: () -> Unit, message: (String) -> Unit) {
    val apps = s.strings("apps")
    val scope = rememberCoroutineScope()
    if (apps.isEmpty()) {
        Text("Pin apps under Back screen → App handoff.")
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
                    if (result.isNotEmpty()) message(result) else close()
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
    for (i in 1..2) if (s.value("ring$i", "Session") != "Hidden" &&
        (s.value("usageFile$i").isNotBlank() ||
            s.value("usageUrl$i").isNotBlank() ||
            s.value("ring$i") == "Battery")) {
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
                    if (s.flag("companion$i", true))
                        CompanionBadge(
                            s.value("mascot$i", if (i == 1) "Sprout" else "Firefly"),
                            ink(s, "Ring $i", MaterialTheme.colorScheme.primary),
                            value.percent >= 75f,
                        )
                    Text(
                        if (reset) "${remaining/60000} min to reset"
                        else "${value.percent.toInt()}%",
                    )
                }
            }
        }
    }
    if (s.value("haBase").isBlank() &&
        s.value("feed").isBlank() &&
        (1..2).none {
            s.value("usageFile$it").isNotBlank() ||
                s.value("usageUrl$it").isNotBlank() ||
                s.value("ring$it") == "Battery"
        })
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
