package io.github.xiaomibutton.remapper.backscreen.dashboard

import android.graphics.Insets
import android.util.Size
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.github.xiaomibutton.remapper.PanelShape
import io.github.xiaomibutton.remapper.R
import io.github.xiaomibutton.remapper.RemapperTheme
import io.github.xiaomibutton.remapper.backscreen.core.BackScreen
import io.github.xiaomibutton.remapper.backscreen.wallpaper.ClockLayer
import io.github.xiaomibutton.remapper.backscreen.wallpaper.PreviewBackdrop
import io.github.xiaomibutton.remapper.backscreen.wallpaper.RearPreviewLayout
import io.github.xiaomibutton.remapper.backscreen.wallpaper.WallpaperView
import kotlinx.coroutines.delay

@Composable
internal fun DashboardPreview(
    s: DashboardStore,
    page: Page?,
    ambient: Boolean,
    shortcuts: Boolean,
    chooseWallpaper: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val activity = LocalActivity.current ?: return
    val connected = remember(activity) { BackScreen.findRearDisplay(activity) }
    val size = remember(connected) { connected?.let(BackScreen::naturalSize) ?: Size(976, 596) }
    val insets =
        remember(connected) { connected?.let(BackScreen::naturalCameraInsets) ?: Insets.NONE }
    val rearDensity =
        remember(connected) {
            connected?.let { activity.createDisplayContext(it).resources.displayMetrics.density }
                ?: 2.75f
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var renderer by remember { mutableStateOf<PreviewBackdrop?>(null) }
    var blank by remember { mutableStateOf(false) }
    var visible by remember { mutableStateOf(false) }
    val shownPage by rememberUpdatedState(page)
    val shownAmbient by rememberUpdatedState(ambient)
    val shownShortcuts by rememberUpdatedState(shortcuts)
    LaunchedEffect(renderer, lifecycle) {
        val backdrop = renderer ?: return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            visible = true
            backdrop.reload()
            backdrop.setMoving(true)
            try {
                while (true) {
                    delay(1_000)
                    backdrop.refresh()
                }
            } finally {
                visible = false
                backdrop.setMoving(false)
            }
        }
    }
    Surface(
        modifier.aspectRatio(size.width.toFloat() / size.height).semantics {
            contentDescription = "Rear display preview"
        },
        shape = PanelShape,
        color = Color.Black,
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val previewDensity by
                rememberUpdatedState(
                    with(LocalDensity.current) { maxWidth.toPx() * rearDensity / size.width },
                )
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    val frame = RearPreviewLayout(context).apply { maxHeightShare = 1f }
                    val image = WallpaperView(context).apply { id = R.id.rear_preview }
                    val clock = ClockLayer(context).apply { id = R.id.rear_clock }
                    fun layer(content: @Composable () -> Unit) =
                        ComposeView(context).apply {
                            setContent {
                                RemapperTheme {
                                    CompositionLocalProvider(
                                        LocalDensity provides Density(previewDensity, 1f),
                                        content = content)
                            }
                            }
                        }
                    val scene = layer { if (!shownAmbient) Scene(s, false, 0f, lit = visible) }
                    val overlays = layer {
                        val current = shownPage
                        val withRings =
                            shownAmbient ||
                                current != null ||
                                shownShortcuts ||
                                s.flag("wallpaperRings", true)
                        if (withRings) LensRings(s, false, ambient = shownAmbient)
                        val lensWidth =
                            if (withRings && s.flag("lensRings")) {
                                val center =
                                    (s.value("lensX", "16").toFloatOrNull() ?: 16f).coerceIn(
                                        4f, 32f)
                                val radius =
                                    (s.value("lensRadius", "20").toFloatOrNull() ?: 20f).coerceIn(
                                        4f, 24f)
                                (size.width * center + size.height * radius) / 100f
                            } else 0f
                        Box(
                            Modifier.fillMaxSize()
                                .absolutePadding(
                                    left =
                                        (maxOf(insets.left.toFloat(), lensWidth) / rearDensity + 6)
                                            .dp,
                                    top = (insets.top / rearDensity).dp,
                                    right = (insets.right / rearDensity + 6).dp,
                                    bottom = (insets.bottom / rearDensity).dp,
                                ),
                        ) {
                            if (shownAmbient) AmbientFace(s, false) {}
                            else {
                                if (s.glanceClock()) GlanceFace(s, Modifier.align(Alignment.Center))
                                when {
                                    current != null ->
                                        RearDashboard(activity, s, current, false, {}, {}, {})
                                    shownShortcuts -> OverlayTray(s, false, {}, {})
                                    s.pages().isNotEmpty() ->
                                        OverlayShortcut(
                                            {},
                                            Modifier.align(Alignment.BottomEnd)
                                                .padding(end = 10.dp, bottom = 6.dp))
                                }
                            }
                        }
                    }
                    fun FrameLayout.addLayer(view: View) {
                        addView(view, FrameLayout.LayoutParams(-1, -1))
                    }
                    frame.addLayer(image)
                    frame.addLayer(scene)
                    frame.addLayer(clock)
                    frame.addLayer(overlays)
                    renderer =
                        PreviewBackdrop(
                            frame,
                            image,
                            clock,
                            {
                                blank = image.drawable == null && clock.visibility != View.VISIBLE
                            },
                            previewWhileOff = true,
                            showLegacyClock = { !shownAmbient && !s.glanceClock() })
                    object : FrameLayout(context) {
                            override fun onInterceptTouchEvent(ev: MotionEvent?): Boolean = true
                        }
                        .apply {
                            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                            importantForAccessibility =
                                View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                            addLayer(frame)
                        }
                },
                update = { renderer?.refresh() },
                onRelease = {
                    renderer?.release()
                    renderer = null
                },
            )
            if (blank &&
                page == null &&
                !shortcuts &&
                !ambient &&
                !s.glanceClock() &&
                s.value("scene", DashboardStore.WALLPAPER) == DashboardStore.WALLPAPER) {
                FilledTonalButton(chooseWallpaper, Modifier.align(Alignment.Center)) {
                    Text("Choose wallpaper")
                }
            }
        }
    }
}
