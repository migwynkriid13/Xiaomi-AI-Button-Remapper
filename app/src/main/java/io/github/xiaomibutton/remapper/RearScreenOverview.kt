package io.github.xiaomibutton.remapper

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.github.xiaomibutton.remapper.backscreen.core.isRearScreenPlatform
import io.github.xiaomibutton.remapper.backscreen.ui.Feature

@Composable
internal fun RearScreenOverview(modifier: Modifier = Modifier, open: (Feature) -> Unit) {
    val model = xiaomi18ModelLabel(Build.MODEL, Build.DEVICE) ?: xiaomi17ModelLabel(Build.MODEL, Build.DEVICE)
    val supported = isRearScreenPlatform()
    val compact = LocalConfiguration.current.screenHeightDp < 720
    if (compact) {
        Column(
            modifier.fillMaxSize().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            BackScreenHero(model, supported)
            if (!supported) return@Column
            if (model == null) SupportedModelsNote()
            Feature.entries.forEach { feature ->
                RearFeatureCard(feature, open, Modifier.heightIn(min = 72.dp))
            }
        }
    } else {
        Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            BackScreenHero(model, supported)
            if (!supported) return@Column
            if (model == null) SupportedModelsNote()
            Column(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Feature.entries.forEach { feature ->
                    RearFeatureCard(feature, open, Modifier.weight(1f).heightIn(min = 64.dp))
                }
            }
        }
    }
}

@Composable
private fun SupportedModelsNote() {
    QuietText("Supported on Xiaomi 17 Pro, 17 Pro Max, 18 Pro and 18 Pro Max.", Modifier.padding(horizontal = 2.dp))
}

@Composable
private fun RearFeatureCard(feature: Feature, open: (Feature) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val (description, icon) = when (feature) {
        Feature.WALLPAPER -> "Build the backdrop, clock and schedule" to R.drawable.rear_ic_image
        Feature.NOTIFICATIONS -> "Choose what appears and when the display wakes" to R.drawable.rear_ic_notifications
        Feature.BATTERY -> "Shape the charging animation and its color" to R.drawable.rear_ic_battery_charging
        Feature.MIRROR -> "Move the current app with Quick Switch" to R.drawable.rear_ic_swap
        Feature.CAMERA -> "Set up the rear-display camera gesture" to R.drawable.rear_ic_camera
    }
    Surface(
        modifier = modifier.fillMaxWidth().clickable { open(feature) },
        shape = PanelShape,
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ActionGlyph(icon)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(context.getString(feature.label), style = MaterialTheme.typography.titleSmall)
                QuietText(description)
            }
            LineIcon(Mark.NEXT, Modifier.size(16.dp), MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun BackScreenHero(model: String?, supported: Boolean) {
    val mirrored = LocalLayoutDirection.current == LayoutDirection.Rtl
    Panel {
        Box(Modifier.fillMaxWidth().background(Color(0xFF2B100A)), contentAlignment = Alignment.CenterStart) {
            Spacer(Modifier.fillMaxWidth().aspectRatio(2.25f))
            Box(Modifier.matchParentSize().graphicsLayer { scaleX = if (mirrored) -1f else 1f }) {
                Image(
                    painter = painterResource(R.drawable.back_screen_hero),
                    contentDescription = "A phone lying face down with the app's stacked clock and wallpaper on its back screen",
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
internal fun RearOnlyPreferences(
    config: AppConfig,
    change: ((AppConfig) -> AppConfig) -> Unit,
    launch: (Intent) -> Unit,
    startOnBoot: Boolean,
    setStartOnBoot: (Boolean) -> Unit,
    reset: () -> Unit,
) {
    val context = LocalContext.current
    Headline("Settings")
    DiagnosticsPreference(config.showDiagnostics) { change { it.copy(showDiagnostics = !it.showDiagnostics) } }
    StartupPreference(startOnBoot, setStartOnBoot)
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
    GroupTitle("Configuration")
    Panel {
        SettingRow("Reset to defaults", "Restore every app and Back Screen setting to its original value", reset,
            icon = R.drawable.action_replay)
    }
}
