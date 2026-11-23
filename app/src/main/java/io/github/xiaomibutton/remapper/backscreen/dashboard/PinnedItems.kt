package io.github.xiaomibutton.remapper.backscreen.dashboard

import android.content.*
import android.graphics.*
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.*
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.xiaomibutton.remapper.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*

internal fun decode(c: Context, uri: Uri): Bitmap {
    require(uri.scheme == "content") { "Choose an image from the system picker" }
    return ImageDecoder.decodeBitmap(ImageDecoder.createSource(c.contentResolver, uri)) {
        decoder,
        info,
        _ ->
        val side = maxOf(info.size.width, info.size.height)
        require(side > 0)
        if (side > 2048) decoder.setTargetSampleSize((side + 2047) / 2048)
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
    }
}

internal object Pins {
    fun image(s: DashboardStore, id: String): File {
        UUID.fromString(id)
        return File(File(s.context.filesDir, "rear_pins"), "$id.jpg")
    }

    fun remove(s: DashboardStore, id: String) {
        s.save("shares", s.strings("shares") - id)
        s.remove("note.$id")
        image(s, id).delete()
    }

    fun add(s: DashboardStore, id: String, text: String) {
        val existing = s.strings("shares")
        if (text.isNotBlank()) s.set("note.$id", text.take(12000))
        s.save("shares", listOf(id) + existing)
        existing.drop(23).forEach { remove(s, it) }
        s.set("openPin", id)
    }
}

class PinActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val s = DashboardStore(this)
        val incoming = if (intent.action == Intent.ACTION_SEND) intent else null
        @Suppress("DEPRECATION")
        val uri =
            (incoming?.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))?.takeIf {
                val clip = incoming.clipData
                it.scheme == "content" &&
                    incoming.type?.startsWith("image/") == true &&
                    incoming.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0 &&
                    clip != null &&
                    (0 until clip.itemCount).any { index -> clip.getItemAt(index).uri == it }
            }
        val text =
            incoming?.getCharSequenceExtra(Intent.EXTRA_TEXT)?.take(12000)?.toString().orEmpty()
        setContent {
            RemapperTheme { Surface(Modifier.fillMaxSize()) { PinEditor(s, uri, text, ::finish) } }
        }
    }
}

@Composable
private fun PinEditor(s: DashboardStore, initial: Uri?, initialText: String, done: () -> Unit) {
    var text by remember { mutableStateOf(initialText) }
    var uri by remember { mutableStateOf(initial) }
    var error by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri = it }
    val bitmap by
        produceState<Bitmap?>(null, uri) {
            value = null
            val selected = uri ?: return@produceState
            value =
                withContext(Dispatchers.IO) {
                    runCatching { decode(s.context, selected) }.getOrNull()
                }
            if (value == null) error = "This image could not be opened."
        }
    var horizontal by remember(uri) { mutableStateOf(0f..1f) }
    var vertical by remember(uri) { mutableStateOf(0f..1f) }
    val crop =
        remember(bitmap, horizontal, vertical) {
            bitmap?.let { source ->
                val x = (source.width * horizontal.start).toInt().coerceIn(0, source.width - 1)
                val y = (source.height * vertical.start).toInt().coerceIn(0, source.height - 1)
                Rect(
                    x,
                    y,
                    (source.width * horizontal.endInclusive).toInt().coerceIn(x + 1, source.width),
                    (source.height * vertical.endInclusive).toInt().coerceIn(y + 1, source.height),
                )
            }
        }
    Column(
        Modifier.systemBarsPadding().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Pin to back screen", style = MaterialTheme.typography.headlineLarge)
        if (uri == null)
            OutlinedTextField(
                text,
                { text = it.take(12000) },
                Modifier.fillMaxWidth(),
                label = { Text("Note") },
                minLines = 3,
            )
        else if (bitmap != null && crop != null) {
            androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().height(170.dp)) {
                val factor = minOf(size.width / crop.width(), size.height / crop.height())
                val width = (crop.width() * factor).toInt().coerceAtLeast(1)
                val height = (crop.height() * factor).toInt().coerceAtLeast(1)
                drawImage(
                    bitmap!!.asImageBitmap(),
                    androidx.compose.ui.unit.IntOffset(crop.left, crop.top),
                    androidx.compose.ui.unit.IntSize(crop.width(), crop.height()),
                    androidx.compose.ui.unit.IntOffset(
                        ((size.width - width) / 2).toInt(),
                        ((size.height - height) / 2).toInt(),
                    ),
                    androidx.compose.ui.unit.IntSize(width, height),
                )
            }
            Text("Horizontal crop")
            RangeSlider(horizontal, { horizontal = it })
            Text("Vertical crop")
            RangeSlider(vertical, { vertical = it })
        }
        TextButton({ picker.launch("image/*") }) { Text("Choose image or screenshot") }
        if (uri != null) TextButton({ uri = null }) { Text("Use text instead") }
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        Button(
            enabled = !saving && (if (uri != null) bitmap != null else text.isNotBlank()),
            onClick = {
                saving = true
                val id = UUID.randomUUID().toString()
                scope.launch {
                    runCatching {
                        val source = bitmap
                        if (source != null && crop != null)
                            withContext(Dispatchers.IO) {
                                val target = Pins.image(s, id)
                                target.parentFile?.mkdirs()
                                val cut =
                                    Bitmap.createBitmap(
                                        source,
                                        crop.left,
                                        crop.top,
                                        crop.width(),
                                        crop.height(),
                                    )
                                target.outputStream().use {
                                    check(cut.compress(Bitmap.CompressFormat.JPEG, 90, it))
                                }
                            }
                        Pins.add(s, id, if (source == null) text else "")
                        done()
                    }
                        .onFailure {
                            Pins.image(s, id).delete()
                            error = "Could not save this pin"
                            saving = false
                        }
                }
            },
        ) {
            Text("Pin to back")
        }
        TextButton(done) { Text("Cancel") }
    }
}

@Composable
internal fun SharedPage(s: DashboardStore) {
    val items = s.strings("shares")
    if (items.isEmpty()) {
        Text("Share a note, ticket or photo to Pin to back screen.")
        return
    }
    val pager = androidx.compose.foundation.pager.rememberPagerState { items.size }
    androidx.compose.foundation.pager.HorizontalPager(
        pager,
        Modifier.fillMaxWidth(),
        key = { items[it] },
    ) { i ->
        val id = items[i]
        val note = s.value("note.$id")
        Column(Modifier.fillMaxWidth()) {
            if (note.isNotEmpty())
                Text(
                    note,
                    fontSize =
                        when (s.value("noteSize")) {
                            "Small" -> 14.sp
                            "Large" -> 24.sp
                            else -> 18.sp
                        },
                )
            else {
                val bitmap by
                    produceState<Bitmap?>(null, id) {
                        value =
                            withContext(Dispatchers.IO) {
                                BitmapFactory.decodeFile(Pins.image(s, id).path)
                            }
                    }
                var scale by remember(id) { mutableFloatStateOf(1f) }
                var pan by remember(id) { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
                bitmap?.let {
                    Image(
                        it.asImageBitmap(),
                        "Pinned photo",
                        Modifier.fillMaxWidth()
                            .height(150.dp)
                            .clipToBounds()
                            .transformable(
                                rememberTransformableState { zoom, offset, _ ->
                                    scale = (scale * zoom).coerceIn(1f, 4f)
                                    pan =
                                        if (scale == 1f) androidx.compose.ui.geometry.Offset.Zero
                                        else pan + offset
                                },
                            )
                            .graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                                translationX = pan.x
                                translationY = pan.y
                            },
                        contentScale = ContentScale.Fit,
                    )
                }
            }
            Row {
                Text("${i+1} / ${items.size}", Modifier.weight(1f))
                TextButton({ Pins.remove(s, id) }) { Text("Remove") }
            }
        }
    }
}
