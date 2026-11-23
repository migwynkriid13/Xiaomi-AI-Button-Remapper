package io.github.xiaomibutton.remapper.backscreen.dashboard

import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.*
import android.view.*
import android.view.inputmethod.EditorInfo
import android.widget.*
import androidx.annotation.RequiresApi
import io.github.xiaomibutton.remapper.R
import io.github.xiaomibutton.remapper.backscreen.core.*
import io.github.xiaomibutton.remapper.runtime.ButtonAccessibilityService

/**
 * Optional keyboard for firmware that keeps the system IME on the front. It only writes to the
 * focused editor of the app lent this display. It never requests editor text or saves keystrokes.
 */
@RequiresApi(35)
internal class CompactKeyboard(private val service: ButtonAccessibilityService) {
    private val handler = Handler(Looper.getMainLooper())
    private var window: LinearLayout? = null
    private var wm: WindowManager? = null
    private var shownFor: String? = null
    private var shift = false
    private var numbers = false
    private var collapsed = false
    private val preferences = service.getSharedPreferences(DashboardStore.FILE, 0)
    private val ownership = service.getSharedPreferences("rear_state", 0)
    private val listener =
        android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> schedule() }
    private val poll =
        object : Runnable {
            override fun run() {
                refresh()
                if (requested()) handler.postDelayed(this, 450)
            }
        }

    private fun activePackage(): String? {
        val prefs = service.getSharedPreferences(DashboardStore.FILE, 0)
        if (
            !prefs.getBoolean("enabled", false) ||
                !prefs.getBoolean("compactKeyboard", false) ||
                PhoneLock.isLocked(service)
        )
            return null
        return RearState.lendFor(service, LendReason.QUICK_SWITCH)?.packageName
    }

    private fun editor(
        pkg: String,
        rear: Int,
    ): android.accessibilityservice.InputMethod.AccessibilityInputConnection? {
        val method = service.inputMethod ?: return null
        if (method.currentInputEditorInfo?.packageName != pkg) return null
        val focused =
            service.windowsOnAllDisplays.get(rear).orEmpty().any {
                it.isFocused &&
                    it.type ==
                        android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION &&
                    it.root?.packageName == pkg
            }
        return if (focused) method.currentInputConnection else null
    }

    private fun requested() =
        preferences.getBoolean("enabled", false) &&
            preferences.getBoolean("compactKeyboard", false) &&
            RearState.lendFor(service, LendReason.QUICK_SWITCH) != null

    private fun schedule() {
        handler.removeCallbacks(poll)
        if (requested()) handler.post(poll)
        else {
            hide()
            service.requestRearEditor(false)
        }
    }

    fun start() {
        preferences.registerOnSharedPreferenceChangeListener(listener)
        ownership.registerOnSharedPreferenceChangeListener(listener)
        schedule()
    }

    fun stop() {
        preferences.unregisterOnSharedPreferenceChangeListener(listener)
        ownership.unregisterOnSharedPreferenceChangeListener(listener)
        handler.removeCallbacks(poll)
        hide()
        service.requestRearEditor(false)
    }

    private fun hide() {
        window?.let { runCatching { wm?.removeViewImmediate(it) } }
        window = null
        wm = null
        shownFor = null
    }

    private fun refresh() {
        val pkg = activePackage()
        service.requestRearEditor(pkg != null)
        val rear = if (pkg != null) BackScreen.findRearDisplay(service) else null
        if (pkg == null || rear == null || editor(pkg, rear.displayId) == null) {
            hide()
            collapsed = false
            return
        }
        if (window != null && shownFor == pkg) return
        hide()
        shownFor = pkg
        val ctx =
            service
                .createDisplayContext(rear)
                .createWindowContext(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, null)
        val manager = ctx.getSystemService(WindowManager::class.java)
        val box =
            LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                val cam = BackScreen.cameraInsets(rear)
                setPadding(cam.left + 3, 2, cam.right + 3, cam.bottom + 2)
                setBackgroundColor(ctx.getColor(R.color.remapper_surface_container))
            }
        fun draw() {
            box.removeAllViews()
            val rows =
                if (collapsed) listOf(listOf("Keyboard"))
                else if (numbers)
                    listOf(
                        "1234567890".map { it.toString() },
                        "@#&()-!?.".map { it.toString() },
                        listOf("ABC", "Space", "⌫", "Enter", "Hide"),
                    )
                else
                    listOf(
                        "qwertyuiop".map { it.toString() },
                        "asdfghjkl".map { it.toString() },
                        "zxcvbnm".map { it.toString() },
                        listOf("⇧", "123", "Space", "⌫", "Enter", "Hide"),
                    )
            for (keys in rows) {
                val row = LinearLayout(ctx)
                box.addView(row)
                for (key in keys) {
                    val label = if (shift && key.length == 1) key.uppercase() else key
                    val button =
                        TextView(ctx).apply {
                            text = label
                            gravity = Gravity.CENTER
                            textSize = if (key.length > 2) 11f else 16f
                            setTextColor(ctx.getColor(R.color.remapper_on_surface))
                            background =
                                GradientDrawable().apply {
                                    setColor(ctx.getColor(R.color.remapper_surface))
                                    cornerRadius = 10f
                                }
                            isClickable = true
                            isFocusable = true
                            setOnClickListener {
                                if (activePackage() != pkg) return@setOnClickListener
                                val input = editor(pkg, rear.displayId) ?: return@setOnClickListener
                                when (key) {
                                    "Keyboard",
                                    "Hide" -> {
                                        collapsed = !collapsed
                                        draw()
                                    }
                                    "⇧" -> {
                                        shift = !shift
                                        draw()
                                    }
                                    "123",
                                    "ABC" -> {
                                        numbers = !numbers
                                        draw()
                                    }
                                    "Space" -> input.commitText(" ", 1, null)
                                    "⌫" -> input.deleteSurroundingText(1, 0)
                                    "Enter" -> {
                                        val action =
                                            (service.inputMethod?.currentInputEditorInfo?.imeOptions
                                                ?: 0) and EditorInfo.IME_MASK_ACTION
                                        if (action > EditorInfo.IME_ACTION_NONE)
                                            input.performEditorAction(action)
                                        else input.commitText("\n", 1, null)
                                    }
                                    else -> {
                                        input.commitText(label, 1, null)
                                        if (shift) {
                                            shift = false
                                            draw()
                                        }
                                    }
                                }
                            }
                        }
                    val h = (ctx.resources.displayMetrics.density * 32).toInt()
                    row.addView(
                        button,
                        LinearLayout.LayoutParams(0, h, if (key == "Space") 2f else 1f).apply {
                            setMargins(1, 1, 1, 1)
                        },
                    )
                }
            }
        }
        draw()
        val p =
            WindowManager.LayoutParams(
                -1,
                -2,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT,
            )
        p.gravity = Gravity.BOTTOM
        runCatching { manager.addView(box, p) }
            .onSuccess {
                window = box
                wm = manager
            }
    }
}
