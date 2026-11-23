package io.github.xiaomibutton.remapper.backscreen.dashboard

import android.app.KeyguardManager
import android.content.*
import android.hardware.*
import android.hardware.display.DisplayManager
import android.os.*
import android.view.*
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import io.github.xiaomibutton.remapper.RemapperTheme
import io.github.xiaomibutton.remapper.backscreen.core.BackScreen
import io.github.xiaomibutton.remapper.backscreen.core.PhoneLock
import io.github.xiaomibutton.remapper.backscreen.rear.BackSensor

internal class DashboardOverlay(
    private val a: ComponentActivity,
    private val onSettingsChanged: () -> Unit,
) : FrameLayout(a), SensorEventListener, DisplayManager.DisplayListener {
    private val store = DashboardStore(a)

    private sealed interface Panel {
        data object Shortcuts : Panel

        data class Content(val page: Page) : Panel
    }

    private var panel by mutableStateOf<Panel?>(null)
    private val page
        get() = (panel as? Panel.Content)?.page

    private var lit by mutableStateOf(false)
    var interrupted by mutableFloatStateOf(0f)
    val usesGlanceClock
        get() = store.glanceClock()

    val backdrop = ComposeView(a)
    private var dim by mutableStateOf(false)
    private var covered by mutableStateOf(false)
    private var locked by mutableStateOf(true)
    private var faceDown by mutableStateOf(false)
    private var tilt by mutableFloatStateOf(0f)
    private var profile by mutableStateOf<String?>(null)
    private var lastTouch = SystemClock.elapsedRealtime()
    private var oldBrightness = -1f
    private var ownedAwake = false
    private var running by mutableStateOf(false)
    private var lastPin = store.value("openPin")
    private val handler = Handler(Looper.getMainLooper())
    private val sensors = a.getSystemService(SensorManager::class.java)
    private val dm = a.getSystemService(DisplayManager::class.java)
    private val compose = ComposeView(a)
    private val rings = ComposeView(a)
    private val receiver =
        object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                refreshPrivacy()
                onSettingsChanged()
            }
        }
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        sync()
        if (running) {
            updateSensors()
            onSettingsChanged()
        }
    }
    val consuming
        get() = (panel != null || dim || covered)

    private val tick =
        object : Runnable {
            override fun run() {
                if (!running) return
                sync()
                handler.postDelayed(this, 1500)
            }
        }

    init {
        backdrop.setContent {
            RemapperTheme {
                if (running && !dim && !covered) Scene(store, faceDown, tilt, lit)
            }
        }
        rings.setContent {
            RemapperTheme {
                if (running &&
                    !covered &&
                    (dim || panel != null || store.flag("wallpaperRings", true)))
                    LensRings(store, locked, ambient = dim)
            }
        }
        addView(rings, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(compose, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        compose.setContent {
            RemapperTheme {
                if (running) {
                    Box(Modifier.fillMaxSize()) {
                        when {
                            covered -> Unit
                            dim -> AmbientFace(store, locked, ::touch)
                            else -> {
                                if (usesGlanceClock)
                                    GlanceFace(
                                        store,
                                        Modifier.align(Alignment.Center).alpha(1f - interrupted))
                                when (val shown = panel) {
                                    Panel.Shortcuts -> OverlayTray(store, locked, ::open, ::close)
                                    is Panel.Content ->
                                        RearDashboard(
                                            a,
                                            store,
                                            shown.page,
                                            locked,
                                            ::open,
                                            ::close,
                                        ) {
                                            a.getSystemService(KeyguardManager::class.java)
                                                .requestDismissKeyguard(a, null)
                                        }
                                    null ->
                                        if (lit && interrupted == 0f) {
                                            if (!locked)
                                                NowPlayingGlance(
                                                    store,
                                                    { open(Page.MUSIC) },
                                                    Modifier.align(Alignment.BottomStart)
                                                        .fillMaxWidth(.63f)
                                                        .padding(start = 8.dp, bottom = 6.dp))
                                            if (hasShortcuts)
                                                OverlayShortcut(
                                                    ::openShortcuts,
                                                    Modifier.align(Alignment.BottomEnd)
                                                        .padding(end = 10.dp, bottom = 6.dp),
                                                )
                                        }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private val hasShortcuts
        get() = store.pages().any { !locked || it.availableWhenLocked(store) }

    fun resume() {
        if (running) return
        running = true
        DashboardAlerts.urgent = {
            if (!covered) {
                open(Page.LIVE)
            }
        }
        oldBrightness = a.window.attributes.screenBrightness
        store.prefs.registerOnSharedPreferenceChangeListener(prefsListener)
        a.registerReceiver(
            receiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            },
        )
        dm.registerDisplayListener(this, handler)
        updateSensors()
        lit = a.display?.state == Display.STATE_ON
        sync()
        onSettingsChanged()
        handler.post(tick)
    }

    fun pause() {
        if (!running) return
        running = false
        DashboardAlerts.urgent = null
        handler.removeCallbacks(tick)
        dm.unregisterDisplayListener(this)
        sensors.unregisterListener(this)
        a.unregisterReceiver(receiver)
        store.prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        applyWindow(false, oldBrightness)
    }

    private fun updateSensors() {
        sensors.unregisterListener(this)
        covered = false
        if (store.pocketMode())
            BackSensor.find(sensors)?.let {
                sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
            }
        if (store.flag("Table lights") || store.flag("Tilt parallax"))
            sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
                sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
            }
    }

    private fun refreshPrivacy() {
        locked = PhoneLock.isLocked(a)
        if (locked && page?.availableWhenLocked(store) == false) dismiss()
    }

    private fun sync() {
        refreshPrivacy()
        if (page?.let { it !in store.pages() } == true) dismiss()
        val active = activeProfile(store, locked)
        if (active != profile) {
            profile = active
            val target = active?.let { name ->
                Page.entries.find { it.name == store.value("page.$name") }
            }
            if (target != null && target != Page.APPS) open(target)
        }
        val pin = store.value("openPin")
        if (lastPin != pin) {
            lastPin = pin
            if (!locked) open(Page.SHARED)
        }
        val idle = profile?.let { store.value("timeout.$it", "30") } ?: store.value("idle", "30")
        val liveAwake = page == Page.LIVE && store.flag("Keep live page awake")
        val idleExpired =
            SystemClock.elapsedRealtime() - lastTouch > (idle.toLongOrNull() ?: 30) * 1000
        if (idleExpired && !liveAwake) dismiss()
        dim = store.flag("Always-on") && !liveAwake && idleExpired
        val brightness =
            when {
                covered -> 0f
                dim -> .035f
                else ->
                    profile?.let {
                        store.value("brightness.$it").removeSuffix("%").toFloatOrNull()?.div(100)
                    } ?: store.value("panelBrightness").toFloatOrNull() ?: oldBrightness
            }
        applyWindow(!covered && (store.flag("Always-on") || liveAwake), brightness)
        layoutPanel()
    }

    private fun applyWindow(awake: Boolean, brightness: Float) {
        if (awake &&
            !ownedAwake &&
            a.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON == 0) {
            ownedAwake = true
            a.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else if (!awake && ownedAwake) {
            ownedAwake = false
            a.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        if (a.window.attributes.screenBrightness != brightness)
            a.window.attributes = a.window.attributes.apply { screenBrightness = brightness }
    }

    fun touch() {
        lastTouch = SystemClock.elapsedRealtime()
        if (dim) {
            dim = false
            layoutPanel()
        }
    }

    val launcherSwipe
        get() =
            store.flag("edgeLauncher", true) &&
                Page.APPS in store.pages() &&
                store.strings("apps").isNotEmpty() &&
                !locked

    fun openLauncher() {
        if (launcherSwipe && !covered) open(Page.APPS)
    }

    private fun openShortcuts() {
        touch()
        panel = Panel.Shortcuts
        layoutPanel()
    }

    private fun open(target: Page) {
        if (covered || target !in store.pages() || (locked && !target.availableWhenLocked(store)))
            return
        touch()
        panel = Panel.Content(target)
        layoutPanel()
    }

    private fun dismiss() {
        if (panel == null) return
        panel = null
        layoutPanel()
    }

    private fun close() {
        touch()
        dismiss()
    }

    fun back(): Boolean {
        if (!consuming) return false
        if (!covered) close()
        return true
    }

    private fun layoutPanel() {
        val inset = a.display?.let { BackScreen.cameraInsets(it) }
        val horizontalMargin = (6 * resources.displayMetrics.density).toInt()
        val lensWidth =
            if (store.flag("lensRings") &&
                (dim || panel != null || store.flag("wallpaperRings", true))) {
                val center = (store.value("lensX", "16").toFloatOrNull() ?: 16f).coerceIn(4f, 32f)
                val radius =
                    (store.value("lensRadius", "20").toFloatOrNull() ?: 20f).coerceIn(4f, 24f)
                ((width * center + minOf(width, height) * radius) / 100f).toInt()
            } else 0
        compose.setPadding(
            maxOf(inset?.left ?: 0, lensWidth) + horizontalMargin,
            inset?.top ?: 0,
            (inset?.right ?: 0) + horizontalMargin,
            inset?.bottom ?: 0,
        )
        setBackgroundColor(
            if (dim || covered) android.graphics.Color.BLACK
            else android.graphics.Color.TRANSPARENT)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        layoutPanel()
    }

    override fun onSensorChanged(e: SensorEvent) {
        if (e.sensor.type == Sensor.TYPE_ACCELEROMETER) {
            faceDown = e.values[2] < -7
            tilt = (e.values[0] / 10f).coerceIn(-1f, 1f)
        } else {
            val next = BackSensor.isCovered(e)
            if (next != covered) {
                covered = next
                if (!next) touch()
                sync()
            }
        }
    }

    override fun onAccuracyChanged(s: Sensor?, acc: Int) {}

    override fun onDisplayChanged(id: Int) {
        if (id != a.display?.displayId) return
        lit = a.display?.state == Display.STATE_ON
        if (!lit) dismiss()
        refreshPrivacy()
    }

    override fun onDisplayAdded(id: Int) {}

    override fun onDisplayRemoved(id: Int) {}
}
