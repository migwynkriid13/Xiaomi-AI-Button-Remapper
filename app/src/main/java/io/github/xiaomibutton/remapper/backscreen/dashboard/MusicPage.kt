package io.github.xiaomibutton.remapper.backscreen.dashboard

import android.content.ComponentName
import android.media.*
import android.media.session.*
import android.os.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import io.github.xiaomibutton.remapper.MediaSessionListener
import io.github.xiaomibutton.remapper.TileShape
import kotlinx.coroutines.*

@Composable
internal fun player(s: DashboardStore): MediaController? {
    val manager = s.context.getSystemService(MediaSessionManager::class.java)
    var controller by remember { mutableStateOf<MediaController?>(null) }
    DisposableEffect(manager) {
        val component = ComponentName(s.context, MediaSessionListener::class.java)
        fun select(sessions: List<MediaController>?) {
            controller =
                sessions?.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
                    ?: sessions?.firstOrNull()
        }
        val listener = MediaSessionManager.OnActiveSessionsChangedListener(::select)
        runCatching {
            manager.addOnActiveSessionsChangedListener(listener, component)
            select(manager.getActiveSessions(component))
        }
        onDispose { runCatching { manager.removeOnActiveSessionsChangedListener(listener) } }
    }
    return controller
}

internal data class Lyric(val ms: Long, val text: String)

internal fun parseLyrics(text: String): List<Lyric> {
    val pattern = Regex("""\[(\d+):(\d{2})(?:\.(\d{1,3}))?\]""")
    return text
        .lineSequence()
        .flatMap { line ->
            val lyric = line.replace(pattern, "").trim()
            pattern.findAll(line).map { m ->
                Lyric(
                    (m.groupValues[1].toLong() * 60 + m.groupValues[2].toLong()) * 1000 +
                        m.groupValues[3].padEnd(3, '0').toLong(),
                    lyric,
                )
            }
        }
        .sortedBy { it.ms }
        .toList()
}

@Composable
internal fun MusicPage(s: DashboardStore, now: Long) {
    val controller = player(s)
    if (controller == null) {
        Text("Play music or a podcast, and allow the remapper’s media listener in Settings.")
        return
    }
    var revision by remember(controller) { mutableIntStateOf(0) }
    DisposableEffect(controller) {
        val cb =
            object : MediaController.Callback() {
                override fun onMetadataChanged(metadata: MediaMetadata?) {
                    revision++
                }

                override fun onPlaybackStateChanged(state: PlaybackState?) {
                    revision++
                }
            }
        controller.registerCallback(cb)
        onDispose { controller.unregisterCallback(cb) }
    }
    val m = remember(controller, revision) { controller.metadata }
    val state = remember(controller, revision) { controller.playbackState }
    val title = m?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
    val artist = m?.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty()
    val album = m?.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty()
    val art =
        m?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: m?.getBitmap(MediaMetadata.METADATA_KEY_ART)
    val duration = m?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
    val playing = state?.state == PlaybackState.STATE_PLAYING
    val pos =
        if (state == null) 0L
        else
            (state.position +
                    if (playing)
                        ((SystemClock.elapsedRealtime() - state.lastPositionUpdateTime) *
                                state.playbackSpeed)
                            .toLong()
                    else 0L)
                .coerceIn(0L, duration.coerceAtLeast(0))
    val colors = MaterialTheme.colorScheme
    val record = s.value("art") == "Record"
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (art != null) {
            val rotation =
                if (playing && record)
                    rememberInfiniteTransition(label = "Record")
                        .animateFloat(
                            0f,
                            360f,
                            infiniteRepeatable(tween(10000, easing = LinearEasing)),
                            label = "Turn",
                        )
                        .value
                else 0f
            Image(
                art.asImageBitmap(),
                "Album cover",
                Modifier.size(64.dp).clip(if (record) CircleShape else TileShape).rotate(rotation),
                contentScale = ContentScale.Crop,
            )
        }
        Column {
            Text(
                title.ifBlank { "Now playing" },
                color = ink(s, "Title", colors.onSurface),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(artist, color = ink(s, "Artist", colors.onSurfaceVariant))
        }
    }
    val transport = controller.transportControls
    val actions = state?.actions ?: 0L
    Row {
        TextButton(
            { transport.skipToPrevious() },
            enabled = actions and PlaybackState.ACTION_SKIP_TO_PREVIOUS != 0L,
        ) {
            Text("Previous", color = ink(s, "Transport", colors.primary))
        }
        Button({ if (playing) transport.pause() else transport.play() }) {
            Text(if (playing) "Pause" else "Play")
        }
        TextButton(
            { transport.skipToNext() },
            enabled = actions and PlaybackState.ACTION_SKIP_TO_NEXT != 0L,
        ) {
            Text("Next", color = ink(s, "Transport", colors.primary))
        }
    }
    if (duration > 0) {
        var dragging by remember(title) { mutableStateOf<Float?>(null) }
        fun stamp(ms: Long): String = android.text.format.DateUtils.formatElapsedTime(ms / 1000)
        Text(
            stamp(dragging?.let { (it * duration).toLong() } ?: pos) + " / " + stamp(duration),
            style = MaterialTheme.typography.labelMedium,
        )
        Slider(
            dragging ?: pos.toFloat() / duration,
            { dragging = it },
            enabled = actions and PlaybackState.ACTION_SEEK_TO != 0L,
            onValueChangeFinished = {
                dragging?.let { transport.seekTo((it * duration).toLong()) }
                dragging = null
            },
            colors = SliderDefaults.colors(activeTrackColor = ink(s, "Progress", colors.primary)),
        )
    }
    val audio = s.context.getSystemService(AudioManager::class.java)
    val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
    var volume by
        remember(now) {
            mutableFloatStateOf(audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat())
        }
    Text("Volume · ${volume.toInt()}", style = MaterialTheme.typography.labelLarge)
    Slider(
        volume,
        {
            volume = it
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, it.toInt(), 0)
        },
        valueRange = 0f..max.toFloat(),
        colors = SliderDefaults.colors(activeTrackColor = ink(s, "Volume", colors.primary)),
    )
    if (s.flag("lyrics") && title.isNotBlank()) {
        val lines by
            produceState<List<Lyric>?>(null, title, artist, album) {
                value =
                    withContext(Dispatchers.IO) {
                        runCatching {
                                val json =
                                    webJson(
                                        "https://lrclib.net/api/get?track_name=${query(title)}&artist_name=${query(artist)}&album_name=${query(album)}&duration=${duration/1000}",
                                    )
                                parseLyrics(json.optString("syncedLyrics"))
                            }
                            .getOrDefault(emptyList())
                    }
            }
        Text(
            lines?.lastOrNull { it.ms <= pos }?.text
                ?: if (lines?.isEmpty() == true) "No synced lyrics" else "Waiting for lyrics",
            color = ink(s, "Lyrics", colors.onSurface),
        )
    }
}
