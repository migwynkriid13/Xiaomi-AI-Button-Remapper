package io.github.xiaomibutton.remapper.backscreen.dashboard

import android.accessibilityservice.AccessibilityService
import android.content.*
import android.graphics.Bitmap
import android.view.Display
import androidx.core.content.FileProvider
import io.github.xiaomibutton.remapper.backscreen.core.PhoneLock
import io.github.xiaomibutton.remapper.runtime.ButtonAccessibilityService
import java.io.File
import kotlinx.coroutines.*

internal object FrontCapture {
    fun request(): String {
        val service =
            ButtonAccessibilityService.connected
                ?: error("Enable the remapper’s optional Accessibility service")
        check(!PhoneLock.isLocked(service)) { "Unlock the phone before capturing" }
        service.takeScreenshot(
            Display.DEFAULT_DISPLAY,
            service.mainExecutor,
            object : AccessibilityService.TakeScreenshotCallback {
                override fun onFailure(errorCode: Int) {
                    android.widget.Toast.makeText(
                            service,
                            "Screen capture unavailable ($errorCode)",
                            android.widget.Toast.LENGTH_SHORT,
                        )
                        .show()
                }

                override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                    val buffer = result.hardwareBuffer
                    val bitmap =
                        try {
                            Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                                ?.copy(Bitmap.Config.ARGB_8888, false)
                        } finally {
                            buffer.close()
                        } ?: return
                    CoroutineScope(Dispatchers.Main.immediate).launch {
                        runCatching {
                            val target =
                                withContext(Dispatchers.IO) {
                                    val folder =
                                        File(service.cacheDir, "rear_crop").apply { mkdirs() }
                                    folder
                                        .listFiles()
                                        ?.filter {
                                            it.lastModified() < System.currentTimeMillis() - 3600000
                                        }
                                        ?.forEach { it.delete() }
                                    File.createTempFile("capture-", ".png", folder).also { file ->
                                        file.outputStream().use {
                                            check(
                                                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it),
                                            )
                                        }
                                    }
                                }
                            if (PhoneLock.isLocked(service)) {
                                target.delete()
                                return@runCatching
                            }
                            val uri =
                                FileProvider.getUriForFile(
                                    service,
                                    service.packageName + ".rear-pins",
                                    target,
                                )
                            service.startActivity(
                                Intent(service, PinActivity::class.java)
                                    .setAction(Intent.ACTION_SEND)
                                    .setType("image/png")
                                    .putExtra(Intent.EXTRA_STREAM, uri)
                                    .apply { clipData = ClipData.newRawUri("Screen crop", uri) }
                                    .addFlags(
                                        Intent.FLAG_ACTIVITY_NEW_TASK or
                                            Intent.FLAG_GRANT_READ_URI_PERMISSION,
                                    ),
                            )
                        }
                            .onFailure {
                                android.widget.Toast.makeText(
                                        service,
                                        "Could not open the crop editor",
                                        android.widget.Toast.LENGTH_SHORT,
                                    )
                                    .show()
                            }
                        bitmap.recycle()
                    }
                }
            },
        )
        return "Choose the area to pin"
    }
}
