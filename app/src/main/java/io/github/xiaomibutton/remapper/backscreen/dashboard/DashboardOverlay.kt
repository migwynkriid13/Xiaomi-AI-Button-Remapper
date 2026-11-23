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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import io.github.xiaomibutton.remapper.RemapperTheme
import io.github.xiaomibutton.remapper.backscreen.core.BackScreen
import io.github.xiaomibutton.remapper.backscreen.core.PhoneLock
import io.github.xiaomibutton.remapper.backscreen.rear.BackSensor

internal class DashboardOverlay(private val a: ComponentActivity) :
    FrameLayout(a), SensorEventListener, DisplayManager.DisplayListener {
    private val store = DashboardStore(a)
    private var page by mutableStateOf<Page?>(null)
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
            }
        }
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        sync()
        if (running) updateSensors()
    }
    val consuming
        get() = store.flag("enabled") && (page != null || dim || covered)

    private val tick =
        object : Runnable {
            override fun run() {
                if (!running) return
                sync()
                handler.postDelayed(this, 1500)
            }
        }

    init {
        rings.setContent {
            RemapperTheme {
                if (
                    running &&
                        store.flag("enabled") &&
                        !covered &&
                        (dim || page != null || store.flag("wallpaperRings", true))
                )
                    LensRings(store, locked, ambient = dim)
            }
        }
        addView(rings, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(compose)
        compose.setContent {
            RemapperTheme {
                if (running && store.flag("enabled"))
                    when {
                        covered ->
                            Surface(
                                Modifier.fillMaxSize(),
                                color = androidx.compose.ui.graphics.Color.Black,
                            ) {}
                        dim ->
                            AmbientFace(store, locked) {
                                touch()
                                open(Page.HOME)
                            }
                        page != null ->
                            RearDashboard(
                                a,
                                store,
                                page!!,
                                locked,
                                faceDown,
                                tilt,
                                ::open,
                                ::close,
                            ) {
                                a.getSystemService(KeyguardManager::class.java)
                                    .requestDismissKeyguard(a, null)
                            }
                        else ->
                            FilledTonalButton(
                                onClick = {
                                    touch()
                                    open(Page.HOME)
                                },
                            ) {
                                Text("Pages")
                            }
                    }
            }
        }
        layoutPanel()
    }

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
        sync()
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
        if (!store.flag("enabled")) return
        if (store.flag("Pocket mode", true))
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
    }

    private fun sync() {
        refreshPrivacy()
        if (!store.flag("enabled")) {
            page = null
            dim = false
            applyWindow(false, oldBrightness)
            layoutPanel()
            return
        }
        val active = activeProfile(store, locked)
        if (active != profile) {
            profile = active
            active?.let {
                open(
                    runCatching { Page.valueOf(store.value("page.$it", "HOME")) }
                        .getOrDefault(Page.HOME),
                )
            }
        }
        val pin = store.value("openPin")
        if (lastPin != pin) {
            lastPin = pin
            if (!locked && "SHARED" in store.pages().map { it.name }) open(Page.SHARED)
        }
        val idle = profile?.let { store.value("timeout.$it", "30") } ?: store.value("idle", "30")
        val liveAwake = page == Page.LIVE && store.flag("Keep live page awake")
        dim =
            store.flag("Always-on") &&
                !liveAwake &&
                SystemClock.elapsedRealtime() - lastTouch > (idle.toLongOrNull() ?: 30) * 1000
        if (
            !dim &&
                !liveAwake &&
                active != null &&
                !store.flag("Always-on") &&
                SystemClock.elapsedRealtime() - lastTouch > (idle.toLongOrNull() ?: 30) * 1000 &&
                page != null
        )
            close()
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
        if (
            awake &&
                !ownedAwake &&
                a.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON == 0
        ) {
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
            store.flag("enabled") && store.flag("edgeLauncher", true) && Page.APPS in store.pages()

    fun openLauncher() {
        if (launcherSwipe && !covered) open(Page.APPS)
    }

    private fun open(target: Page) {
        touch()
        page = target
        layoutPanel()
    }

    private fun close() {
        touch()
        page = null
        layoutPanel()
    }

    fun back(): Boolean {
        if (!consuming) return false
        if (!covered) close()
        return true
    }

    private fun layoutPanel() {
        val full = consuming
        val sz = if (full) LayoutParams.MATCH_PARENT else LayoutParams.WRAP_CONTENT
        val params = compose.layoutParams as LayoutParams
        val gravity = if (full) Gravity.FILL else Gravity.CENTER_VERTICAL or Gravity.END
        if (params.width != sz || params.height != sz || params.gravity != gravity) {
            compose.layoutParams =
                LayoutParams(sz, sz, gravity).apply {
                    if (!full) marginEnd = (16 * resources.displayMetrics.density).toInt()
                }
        }
        val inset = a.display?.let { BackScreen.cameraInsets(it) }
        if (full && !covered) {
            val lensWidth =
                if (store.flag("lensRings")) {
                    val center =
                        (store.value("lensX", "16").toFloatOrNull() ?: 16f).coerceIn(4f, 32f)
                    val radius =
                        (store.value("lensRadius", "20").toFloatOrNull() ?: 20f).coerceIn(4f, 24f)
                    ((width * center + minOf(width, height) * radius) / 100f).toInt()
                } else 0
            compose.setPadding(
                maxOf(inset?.left ?: 0, lensWidth),
                inset?.top ?: 0,
                inset?.right ?: 0,
                inset?.bottom ?: 0,
            )
        } else compose.setPadding(0, 0, 0, 0)
        setBackgroundColor(
            if (full) a.getColor(io.github.xiaomibutton.remapper.R.color.window_background)
            else android.graphics.Color.TRANSPARENT,
        )
        if (dim || covered) setBackgroundColor(android.graphics.Color.BLACK)
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
        refreshPrivacy()
    }

    override fun onDisplayAdded(id: Int) {}

    override fun onDisplayRemoved(id: Int) {}
}
