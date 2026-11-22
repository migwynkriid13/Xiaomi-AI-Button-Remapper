package io.github.xiaomibutton.remapper

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.github.xiaomibutton.remapper.backscreen.core.isRearScreenPlatform
import io.github.xiaomibutton.remapper.backscreen.ui.Feature

@Composable
internal fun RearScreenOverview(open: (Feature) -> Unit) {
    val context = LocalContext.current
    val model = xiaomi18ModelLabel(Build.MODEL, Build.DEVICE) ?: xiaomi17ModelLabel(Build.MODEL, Build.DEVICE)
    val supported = isRearScreenPlatform()
    BackScreenHero(model, supported)
    if (!supported) return
    if (model == null) {
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
private fun BackScreenHero(model: String?, supported: Boolean) {
    val mirrored = LocalLayoutDirection.current == LayoutDirection.Rtl
    Panel {
        Box(Modifier.fillMaxWidth().background(Color(0xFF2B100A)), contentAlignment = Alignment.CenterStart) {
            Box(Modifier.matchParentSize().graphicsLayer { scaleX = if (mirrored) -1f else 1f }) {
                Image(
                    painter = painterResource(R.drawable.back_screen_hero),
                    contentDescription = "A phone lying face down with a sunset wallpaper and clock on its back screen",
                    modifier = Modifier.matchParentSize(),
                    alignment = AbsoluteAlignment.CenterRight,
                    contentScale = ContentScale.Crop
                )
                Box(Modifier.matchParentSize().background(Brush.horizontalGradient(
                    0f to Color(0xE6230D08), 0.37f to Color(0x7A230D08), 0.60f to Color.Transparent
                )))
            }
            Column(
                Modifier.fillMaxWidth(.49f).heightIn(min = 158.dp)
                    .padding(start = 20.dp, end = 6.dp, top = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterVertically)
            ) {
                if (model != null) Text(model, style = MaterialTheme.typography.labelLarge, color = Color(0xFFFFDBCA))
                Text("Back screen", Modifier.semantics { heading() }, color = Color(0xFFFFF8F4),
                    style = MaterialTheme.typography.headlineLarge)
                if (!supported) {
                    Text("Requires Android 15 or later", style = MaterialTheme.typography.bodyMedium, color = Color(0xFFFFDBCA))
                }
            }
        }
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
