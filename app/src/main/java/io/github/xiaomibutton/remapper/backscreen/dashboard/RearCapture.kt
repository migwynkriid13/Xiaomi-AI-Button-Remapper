package io.github.xiaomibutton.remapper.backscreen.dashboard

import android.app.Activity
import android.content.ContentValues
import android.graphics.Bitmap
import android.media.*
import android.os.*
import android.provider.MediaStore
import android.view.PixelCopy
import io.github.xiaomibutton.remapper.backscreen.core.PhoneLock
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.*

/**
 * Capture only the explicitly selected app-owned rear window. Finish a partial recording on Stop.
 */
internal object RearCapture {
    private suspend fun frame(a: Activity, w: Int, h: Int): Bitmap =
        withContext(Dispatchers.Main.immediate) {
            check(a.display?.displayId != 0 && a.hasWindowFocus() && !PhoneLock.isLocked(a)) {
                "Unlock and open the rear dashboard"
            }
            suspendCancellableCoroutine { continuation ->
                val output = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                PixelCopy.request(
                    a.window,
                    output,
                    { code ->
                        if (!continuation.isActive) output.recycle()
                        else if (code == PixelCopy.SUCCESS && !PhoneLock.isLocked(a))
                            continuation.resume(output)
                        else {
                            output.recycle()
                            continuation.resumeWithException(
                                IllegalStateException("Rear capture unavailable"),
                            )
                        }
                    },
                    Handler(Looper.getMainLooper()),
                )
            }
        }

    private fun publish(a: Activity, file: File, movie: Boolean) {
        val resolver = a.contentResolver
        val collection =
            if (movie) MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val uri =
            checkNotNull(
                resolver.insert(
                    collection,
                    ContentValues().apply {
                        put(
                            MediaStore.MediaColumns.DISPLAY_NAME,
                            "Rear-${System.currentTimeMillis()}." + if (movie) "mp4" else "png",
                        )
                        put(
                            MediaStore.MediaColumns.MIME_TYPE,
                            if (movie) "video/mp4" else "image/png",
                        )
                        put(
                            MediaStore.MediaColumns.RELATIVE_PATH,
                            if (movie) "Movies/Rear dashboard" else "Pictures/Rear dashboard",
                        )
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    },
                ),
            )
        try {
            checkNotNull(resolver.openOutputStream(uri)).use { out ->
                file.inputStream().use { it.copyTo(out) }
            }
            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null,
                null,
            )
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }

    suspend fun photo(a: Activity) {
        val bitmap = frame(a, a.window.decorView.width, a.window.decorView.height)
        withContext(Dispatchers.IO) {
            val file = File.createTempFile("rear-", ".png", a.cacheDir)
            try {
                file.outputStream().use {
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
                publish(a, file, false)
            } finally {
                file.delete()
                bitmap.recycle()
            }
        }
    }

    suspend fun record(a: Activity) =
        withContext(Dispatchers.Default) {
            val width = 480
            val height =
                (width * a.window.decorView.height / a.window.decorView.width / 2 * 2).coerceIn(
                    96,
                    640,
                )
            val file = File.createTempFile("rear-", ".mp4", a.cacheDir)
            val encoder = MediaCodec.createEncoderByType("video/avc")
            val mux = MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var track = -1
            var frames = 0
            var started = false
            val info = MediaCodec.BufferInfo()
            fun drain(wait: Long): Boolean {
                while (true) {
                    val i = encoder.dequeueOutputBuffer(info, wait)
                    if (i == MediaCodec.INFO_TRY_AGAIN_LATER) return false
                    if (i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        track = mux.addTrack(encoder.outputFormat)
                        mux.start()
                        continue
                    }
                    if (i >= 0) {
                        if (track >= 0 &&
                            info.size > 0 &&
                            info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            mux.writeSampleData(
                                track,
                                checkNotNull(encoder.getOutputBuffer(i)),
                                info,
                            )
                            frames++
                        }
                        val end = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        encoder.releaseOutputBuffer(i, false)
                        if (end) return true
                    }
                }
            }
            try {
                val format =
                    MediaFormat.createVideoFormat("video/avc", width, height).apply {
                        setInteger(
                            MediaFormat.KEY_COLOR_FORMAT,
                            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible,
                        )
                        setInteger(MediaFormat.KEY_BIT_RATE, 1000000)
                        setInteger(MediaFormat.KEY_FRAME_RATE, 8)
                        setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                    }
                encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                encoder.start()
                started = true
                val start = SystemClock.elapsedRealtime()
                while (SystemClock.elapsedRealtime() - start < 60000 &&
                    currentCoroutineContext().isActive) {
                    val bitmap = frame(a, width, height)
                    try {
                        val index = encoder.dequeueInputBuffer(50000)
                        if (index >= 0) {
                            val img = checkNotNull(encoder.getInputImage(index))
                            val pixels = IntArray(width * height)
                            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
                            for (y in 0 until height) for (x in 0 until width) {
                                val p = pixels[y * width + x]
                                val r = p shr 16 and 255
                                val g = p shr 8 and 255
                                val b = p and 255
                                fun write(plane: Int, px: Int, py: Int, v: Int) {
                                    val dest = img.planes[plane]
                                    dest.buffer.put(
                                        py * dest.rowStride + px * dest.pixelStride,
                                        v.coerceIn(0, 255).toByte(),
                                    )
                                }
                                write(0, x, y, 16 + ((66 * r + 129 * g + 25 * b + 128) shr 8))
                                if ((x or y) and 1 == 0) {
                                    write(
                                        1,
                                        x / 2,
                                        y / 2,
                                        128 + ((-38 * r - 74 * g + 112 * b + 128) shr 8),
                                    )
                                    write(
                                        2,
                                        x / 2,
                                        y / 2,
                                        128 + ((112 * r - 94 * g - 18 * b + 128) shr 8),
                                    )
                                }
                            }
                            encoder.queueInputBuffer(
                                index,
                                0,
                                width * height * 3 / 2,
                                (SystemClock.elapsedRealtime() - start) * 1000,
                                0,
                            )
                        }
                        drain(0)
                    } finally {
                        bitmap.recycle()
                    }
                    delay(125)
                }
            } finally {
                try {
                    if (started) {
                        val i = encoder.dequeueInputBuffer(100000)
                        if (i >= 0)
                            encoder.queueInputBuffer(
                                i,
                                0,
                                0,
                                0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                        val until = SystemClock.elapsedRealtime() + 1500
                        while (SystemClock.elapsedRealtime() < until && !drain(10000)) {}
                    }
                    if (track >= 0) mux.stop()
                    if (frames > 0) publish(a, file, true)
                } finally {
                    runCatching { mux.release() }
                    runCatching { encoder.release() }
                    file.delete()
                }
            }
        }
}
