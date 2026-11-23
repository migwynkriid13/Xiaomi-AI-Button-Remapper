package io.github.xiaomibutton.remapper.backscreen.dashboard

import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.viewinterop.AndroidView
import io.github.xiaomibutton.remapper.PanelShape
import io.github.xiaomibutton.remapper.RemapperTheme
import io.github.xiaomibutton.remapper.backscreen.core.BackScreen

@Composable
internal fun DashboardPreview(s: DashboardStore) {
    val activity = s.context as DashboardActivity
    val connected = BackScreen.findRearDisplay(activity)?.mode
    val models =
        mapOf("17 Pro" to (904 to 572), "18 Pro" to (912 to 596), "Pro Max" to (976 to 596))
    var model by rememberSaveable {
        mutableStateOf(if (connected == null) "18 Pro" else "Connected panel")
    }
    val (width, height) =
        models[model]
            ?: connected?.let {
                maxOf(it.physicalWidth, it.physicalHeight) to
                    minOf(it.physicalWidth, it.physicalHeight)
            }
            ?: (912 to 596)
    val pages = s.pages()
    var selected by rememberSaveable { mutableStateOf(pages.firstOrNull()?.title ?: "Always-on") }
    val page by rememberUpdatedState(pages.find { it.title == selected })
    Options(
        "Panel",
        model,
        (if (connected == null) emptyList() else listOf("Connected panel")) + models.keys,
    ) {
        model = it
    }
    Options("Page", selected, pages.map { it.title } + "Always-on") { selected = it }
    Surface(
        Modifier.fillMaxWidth().aspectRatio(width.toFloat() / height),
        shape = PanelShape,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val paneWidth = maxWidth
            val previewDensity by
                rememberUpdatedState(
                    with(LocalDensity.current) { paneWidth.toPx() / (width / 1.65f) },
                )
            Canvas(Modifier.fillMaxSize()) {
                for (y in listOf(.25f, .75f)) drawCircle(
                    Color(0xff302a26),
                    size.height * .15f,
                    Offset(size.width * .15f, size.height * y),
                )
            }
            LensRings(s, false)
            Box(
                Modifier.fillMaxSize().padding(start = paneWidth * .28f).semantics {
                    contentDescription = "Read-only preview of " + selected
                },
            ) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { context ->
                        // Keep preview input, keyboard focus and accessibility actions out of the
                        // live controls.
                        object : FrameLayout(context) {
                                override fun onInterceptTouchEvent(ev: MotionEvent?): Boolean = true
                            }
                            .apply {
                                descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                                importantForAccessibility =
                                    View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                                addView(
                                    ComposeView(context).apply {
                                        setContent {
                                            RemapperTheme {
                                                CompositionLocalProvider(
                                                    LocalDensity provides
                                                        Density(previewDensity, 1f),
                                                ) {
                                                    Box(
                                                        Modifier.fillMaxSize()
                                                            .clearAndSetSemantics {},
                                                    ) {
                                                        val shown = page
                                                        if (shown == null) AmbientFace(s, false) {}
                                                        else
                                                            RearDashboard(
                                                                activity,
                                                                s,
                                                                shown,
                                                                false,
                                                                false,
                                                                0f,
                                                                {},
                                                                {},
                                                                {},
                                                            )
                                                    }
                                                }
                                            }
                                        }
                                    },
                                    FrameLayout.LayoutParams(-1, -1),
                                )
                            }
                    },
                )
            }
        }
    }
    Text(
        "$width × $height pixels · Use the page selector to inspect your layout.",
        style = MaterialTheme.typography.bodySmall,
    )
}
