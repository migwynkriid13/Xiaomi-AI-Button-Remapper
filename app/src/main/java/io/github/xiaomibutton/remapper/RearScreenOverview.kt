package io.github.xiaomibutton.remapper

import android.content.Intent
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.xiaomibutton.remapper.backscreen.core.BackScreen
import io.github.xiaomibutton.remapper.backscreen.core.isRearScreenPlatform
import io.github.xiaomibutton.remapper.backscreen.ui.Feature

@Composable
internal fun RearScreenOverview(open: (Feature) -> Unit) {
    val context = LocalContext.current
    val model = xiaomi18ModelLabel(Build.MODEL, Build.DEVICE) ?: xiaomi17ModelLabel(Build.MODEL, Build.DEVICE)
    val supported = isRearScreenPlatform()
    val panel by produceState<String?>(null, context, supported) {
        if (!supported) return@produceState
        val manager = context.getSystemService(DisplayManager::class.java)
        fun refresh() {
            value = BackScreen.findRearDisplay(context)?.let(BackScreen::naturalSize)?.let {
                "${it.width} × ${it.height}"
            }
        }
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = refresh()
            override fun onDisplayRemoved(displayId: Int) = refresh()
            override fun onDisplayChanged(displayId: Int) = refresh()
        }
        manager.registerDisplayListener(listener, BackScreen.mainHandler)
        refresh()
        awaitDispose { manager.unregisterDisplayListener(listener) }
    }
    Panel(color = MaterialTheme.colorScheme.primaryContainer) {
        Row(
            Modifier.fillMaxWidth().padding(20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val colors = MaterialTheme.colorScheme
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                if (model != null) Text(model, style = MaterialTheme.typography.labelLarge, color = colors.onPrimaryContainer)
                Text("Back screen", style = MaterialTheme.typography.headlineLarge, color = colors.onPrimaryContainer)
                Text(
                    panel?.let { "Rear display detected · $it" } ?: if (supported) "Rear display undetected" else "Requires Android 15 or later",
                    style = MaterialTheme.typography.bodyMedium, color = colors.onPrimaryContainer
                )
            }
            RearScreenIllustration()
        }
    }
    if (!supported) return
    if (model == null && panel == null) {
        QuietText("Supported on Xiaomi 17 Pro, 17 Pro Max, 18 Pro and 18 Pro Max.", Modifier.padding(horizontal = 2.dp))
    }
    Panel {
        Feature.entries.forEachIndexed { index, feature ->
            val (description, icon) = when (feature) {
                Feature.WALLPAPER -> "Images, GIFs, clock and schedules" to R.drawable.rear_ic_image
                Feature.NOTIFICATIONS -> "Alert previews and lock-screen privacy" to R.drawable.rear_ic_notifications
                Feature.BATTERY -> "Charging effects and colors" to R.drawable.rear_ic_battery_charging
                Feature.MIRROR -> "Move apps between displays with Quick Switch" to R.drawable.rear_ic_swap
                Feature.CAMERA -> "Open Xiaomi Camera on the back screen" to R.drawable.rear_ic_camera
            }
            SettingRow(context.getString(feature.label), description, { open(feature) }, icon = icon)
            if (index != Feature.entries.lastIndex) RowDivider(start = 68)
        }
    }
}

@Composable
private fun RearScreenIllustration() {
    val colors = MaterialTheme.colorScheme
    Canvas(Modifier.size(width = 76.dp, height = 100.dp)) {
        val unit = size.width / 76f
        fun point(x: Float, y: Float) = Offset(x * unit, y * unit)
        fun dimensions(w: Float, h: Float) = Size(w * unit, h * unit)
        drawRoundRect(colors.onPrimaryContainer, point(6f, 2f), dimensions(64f, 96f), CornerRadius(13 * unit), style = Stroke(unit))
        drawRoundRect(colors.onPrimaryContainer, point(12f, 9f), dimensions(52f, 34f), CornerRadius(8 * unit))
        drawRoundRect(colors.primaryContainer, point(27f, 13f), dimensions(33f, 26f), CornerRadius(5 * unit))
        drawRoundRect(colors.primary, point(31f, 29f), dimensions(25f, 6f), CornerRadius(3 * unit))
        drawCircle(colors.primaryContainer, 3f * unit, point(19f, 17f))
        drawCircle(colors.primaryContainer, 3f * unit, point(19f, 27f))
    }
}

@Composable
internal fun RearOnlyPreferences(launch: (Intent) -> Unit) {
    val context = LocalContext.current
    Headline("Settings")
    GroupTitle("Permissions and background")
    Panel {
        SettingRow("Notifications", "Manage alerts and background status notifications for the back screen.", {
            launch(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
        }, icon = R.drawable.action_notifications)
        RowDivider(start = 68)
        SettingRow("App info", "Enable autostart and choose No restrictions for battery use when HyperOS offers them.", {
            launch(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
        }, icon = R.drawable.action_info)
    }
}
