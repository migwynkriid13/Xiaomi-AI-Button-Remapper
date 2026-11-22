package io.github.xiaomibutton.remapper

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.accessibilityservice.InputMethod.AccessibilityInputConnection
import android.app.KeyguardManager
import android.content.Context
import android.graphics.Path
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.text.InputType
import android.view.Display
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.annotation.RequiresApi
import io.github.xiaomibutton.remapper.runtime.ButtonAccessibilityService
import io.github.xiaomibutton.remapper.runtime.UiActions
import java.text.BreakIterator
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

internal object InteractionActions {
    private const val INPUT = "Input and gestures"
    private const val KEYBOARD = "Keyboard"
    private const val EDITOR = "Android 13+, Accessibility, and a focused text editor"
    private const val SERVICE = "Enabled Accessibility service"
    private val cursor = listOf("Left", "Right", "Up", "Down", "Previous word", "Next word",
        "Line start", "Line end", "Document start", "Document end")
    private val imeActions = listOf("Current editor action", "Go", "Search", "Send", "Next", "Done", "Previous")
    private fun choices(labels: List<String>) = labels.map { it to it }
    val actions = listOf(
        CatalogAction("input.key_code", "Send a key code to the editor", INPUT, ActionParameter.NUMBER, EDITOR,
            hint = "Android KeyEvent code, for example 66 for Enter"),
        CatalogAction("input.key_event", "Send a key event with modifiers", INPUT, ActionParameter.TEXT, EDITOR,
            hint = "Android key name with optional modifiers, for example CTRL+SHIFT+KEYCODE_Z"),
        CatalogAction("input.text", "Type text", INPUT, ActionParameter.TEXT, EDITOR, hint = "Text to insert in the focused field"),
        CatalogAction("input.tap", "Tap screen", INPUT, ActionParameter.POINT, SERVICE, hint = "x,y in screen percentages, e.g. 50,50. Optional final duration in ms."),
        CatalogAction("input.swipe", "Swipe screen", INPUT, ActionParameter.POINT, SERVICE, hint = "x1,y1,x2,y2 in percentages, e.g. 20,80,80,20. Optional final duration in ms."),
        CatalogAction("input.pinch", "Pinch screen", INPUT, ActionParameter.POINT, SERVICE, hint = "centerX,centerY,startRadius,endRadius in percentages, e.g. 50,50,5,30. Optional duration in ms."),
        CatalogAction("input.cut", "Cut selected text", INPUT, requirement = EDITOR),
        CatalogAction("input.copy", "Copy selected text", INPUT, requirement = EDITOR),
        CatalogAction("input.paste", "Paste into editor", INPUT, requirement = EDITOR),
        CatalogAction("input.select_all", "Select all text", INPUT, requirement = EDITOR),
        CatalogAction("input.select_word", "Select word at cursor", INPUT, requirement = EDITOR),
        CatalogAction("input.cursor", "Move cursor", INPUT, ActionParameter.CHOICE, EDITOR, choices(cursor)),
        CatalogAction("input.consume", "Consume this gesture without an action", INPUT),
        CatalogAction("keyboard.ime_action", "Perform editor action", KEYBOARD, ActionParameter.CHOICE, EDITOR, choices(imeActions)),
        CatalogAction("keyboard.show", "Show keyboard when editing", KEYBOARD, requirement = SERVICE),
        CatalogAction("keyboard.hide", "Hide keyboard", KEYBOARD, requirement = SERVICE),
        CatalogAction("keyboard.toggle", "Toggle keyboard visibility policy", KEYBOARD, requirement = SERVICE),
        CatalogAction("keyboard.picker", "Show keyboard picker", KEYBOARD, requirement = "Android keyboard picker"),
        CatalogAction("keyboard.switch", "Switch keyboard", KEYBOARD, ActionParameter.TEXT, SERVICE,
            hint = "Enabled keyboard service ID, e.g. com.example.keyboard/.InputService"),
        CatalogAction("keyboard.cycle", "Cycle enabled keyboards", KEYBOARD, requirement = SERVICE),
        KeyMapInterop.action("keyboard.cycle_language", "Cycle keyboard language", KEYBOARD),
        CatalogAction("input.element", "Interact with an app element", INPUT, ActionParameter.TEXT, UiActions.REQUIREMENT,
            hint = """{"package":"com.example.app","id":"com.example.app:id/next","action":"click"} · Choose id or exact text and click, long_click, focus, select, scroll_forward or scroll_backward."""),
        TalkBackActions.entry,
    )

    fun enabledImeChoices(context: Context): List<Pair<String, String>> {
        val ime = context.getSystemService(InputMethodManager::class.java) ?: return emptyList()
        return ime.enabledInputMethodList.map { it.id to it.loadLabel(context.packageManager).toString() }
    }

    private fun pointArgs(id: String, raw: String): Pair<List<Float>, Long> {
        val n = if (id == "input.tap") 2 else 4
        val parts = raw.split(',').map(String::trim)
        require(parts.size == n || parts.size == n + 1) { "Enter $n comma-separated percentages and an optional duration" }
        val points = parts.take(n).map { it.toFloatOrNull()?.takeIf { f -> f.isFinite() && f in 0f..100f }
            ?: throw IllegalArgumentException("Coordinates must be numbers from 0 to 100") }
        val duration = if (parts.size == n) (if (n == 2) 70L else 400L) else
            parts.last().toLongOrNull()?.takeIf { it in 30..2000 } ?: throw IllegalArgumentException("Duration must be 30–2000 ms")
        if (id == "input.pinch") require(points[2] != points[3] && points[2] > 0 && points[3] > 0) {
            "Use two different positive pinch radii"
        }
        return points to duration
    }

    private fun keyPair(raw: String): Pair<Int, Int> {
        val parts = raw.uppercase().split('+').map(String::trim)
        val name = parts.last()
        val code = name.toIntOrNull() ?: KeyEvent.keyCodeFromString(if (name.startsWith("KEYCODE_")) name else "KEYCODE_$name")
        require(code in 1..KeyEvent.getMaxKeyCode()) { "Choose a recognized Android KeyEvent code" }
        val meta = parts.dropLast(1).fold(0) { bits, modifier -> bits or when (modifier) {
            "CTRL" -> KeyEvent.META_CTRL_ON
            "ALT" -> KeyEvent.META_ALT_ON
            "SHIFT" -> KeyEvent.META_SHIFT_ON
            "META" -> KeyEvent.META_META_ON
            else -> throw IllegalArgumentException("Supported modifiers are CTRL, ALT, SHIFT and META")
        } }
        return code to meta
    }

    fun validate(id: String, value: String) {
        when (id) {
            "input.element" -> UiActions.validateElement(value)
            "input.tap", "input.swipe", "input.pinch" -> pointArgs(id, value)
            "input.key_event", "input.key_code" -> keyPair(value)
            "keyboard.switch" -> require(android.content.ComponentName.unflattenFromString(value) != null) { "Enter an enabled keyboard service ID" }
        }
    }

    suspend fun execute(context: Context, id: String, value: String): String {
        if (id == "input.consume") return "Gesture consumed"
        val locked = context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked != false
        val awake = context.getSystemService(PowerManager::class.java)?.isInteractive == true
        check(!locked && awake) { "Unlock the screen for this interaction" }
        if (id == "input.element") return UiActions.interact(value)
        if (id == "input.talkback") return TalkBackActions.perform(context, value)
        if (id == "keyboard.cycle_language") return KeyMapInterop.trigger(context, value)
        val ime = checkNotNull(context.getSystemService(InputMethodManager::class.java))
        if (id == "keyboard.picker") {
            ime.showInputMethodPicker()
            return "Android keyboard picker requested"
        }
        val service = checkNotNull(ButtonAccessibilityService.connected) { "Enable Xiaomi AI Button Remapper in Accessibility settings" }
        return when (id) {
            "input.tap", "input.swipe", "input.pinch" -> gesture(service, id, value)
            "keyboard.show", "keyboard.hide", "keyboard.toggle" -> {
                val control = service.softKeyboardController
                val hide = id == "keyboard.hide" || (id == "keyboard.toggle" && control.showMode != AccessibilityService.SHOW_MODE_HIDDEN)
                check(control.setShowMode(if (hide) AccessibilityService.SHOW_MODE_HIDDEN else AccessibilityService.SHOW_MODE_IGNORE_HARD_KEYBOARD)) {
                    "Android rejected the keyboard visibility change"
                }
                if (hide) "Keyboard hidden" else "Keyboard display allowed for focused editors"
            }
            "keyboard.switch", "keyboard.cycle" -> {
                val installed = enabledImeChoices(context)
                check(installed.isNotEmpty()) { "No enabled keyboard is available" }
                val next = if (id == "keyboard.switch") installed.firstOrNull { it.first == value }
                    ?: error("Enable the selected keyboard in Android Settings") else {
                    val current = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
                    installed[(installed.indexOfFirst { it.first == current } + 1) % installed.size]
                }
                check(service.softKeyboardController.switchToInputMethod(next.first)) { "Android rejected this keyboard switch" }
                "Keyboard switched to ${next.second}"
            }
            else -> {
                if (Build.VERSION.SDK_INT < 33) error("Editor actions require Android 13 or later")
                edit(service, id, value)
            }
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun gesture(service: AccessibilityService, id: String, raw: String): String {
        val (points, duration) = pointArgs(id, raw)
        val display = service.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)
            ?: error("Default display unavailable")
        val size = Point().also(display::getRealSize)
        check(size.x > 1 && size.y > 1)
        fun x(i: Int) = points[i] / 100f * (size.x - 1)
        fun y(i: Int) = points[i] / 100f * (size.y - 1)
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        val strokes = GestureDescription.Builder()
        fun add(path: Path) { strokes.addStroke(GestureDescription.StrokeDescription(path, 0, duration)) }
        when (id) {
            "input.tap" -> add(Path().apply { moveTo(x(0), y(1)) })
            "input.swipe" -> add(line(x(0), y(1), x(2), y(3)))
            else -> {
                val unit = minOf(size.x - 1, size.y - 1) / 100f
                val center = x(0)
                val r1 = unit * points[2]
                val r2 = unit * points[3]
                val max = maxOf(r1, r2)
                require(center - max >= 0 && center + max <= size.x - 1) { "Pinch would extend past the display edge" }
                add(line(center - r1, y(1), center - r2, y(1)))
                add(line(center + r1, y(1), center + r2, y(1)))
            }
        }
        val completed = withTimeoutOrNull(duration + 2000) {
            suspendCancellableCoroutine<Boolean> { result ->
                val callback = object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gesture: GestureDescription) { if (result.isActive) result.resume(true) }
                    override fun onCancelled(gesture: GestureDescription) { if (result.isActive) result.resume(false) }
                }
                if (!service.dispatchGesture(strokes.build(), callback, null) && result.isActive) result.resume(false)
            }
        }
        check(completed == true) { "Gesture cancelled or unavailable" }
        return "Touch gesture completed"
    }

    @RequiresApi(33)
    private suspend fun edit(service: ButtonAccessibilityService, id: String, value: String): String {
        service.requestEditor(true)
        try {
            val editor = withTimeoutOrNull(1500) {
                var ready: Pair<AccessibilityInputConnection, EditorInfo>? = null
                while (ready == null) {
                    val method = service.inputMethod
                    val connection = method?.currentInputConnection
                    val info = method?.currentInputEditorInfo
                    if (connection != null && info != null) ready = connection to info else delay(40)
                }
                ready
            } ?: error("Focus an editable text field first")
            val (connection, info) = editor
            when (id) {
                "input.text" -> connection.commitText(value, 1, null)
                "input.cut" -> connection.performContextMenuAction(android.R.id.cut)
                "input.copy" -> connection.performContextMenuAction(android.R.id.copy)
                "input.paste" -> connection.performContextMenuAction(android.R.id.paste)
                "input.select_all" -> connection.performContextMenuAction(android.R.id.selectAll)
                "input.select_word" -> selectWord(connection, info)
                "input.key_code", "input.key_event" -> keyPair(value).also { sendKey(connection, it.first, it.second) }
                "input.cursor" -> {
                    val combination = when (value) {
                        "Left" -> "DPAD_LEFT"; "Right" -> "DPAD_RIGHT"; "Up" -> "DPAD_UP"; "Down" -> "DPAD_DOWN"
                        "Previous word" -> "CTRL+DPAD_LEFT"; "Next word" -> "CTRL+DPAD_RIGHT"
                        "Line start" -> "MOVE_HOME"; "Line end" -> "MOVE_END"
                        "Document start" -> "CTRL+MOVE_HOME"; "Document end" -> "CTRL+MOVE_END"
                        else -> error("Unknown cursor movement")
                    }
                    val (code, meta) = keyPair(combination)
                    sendKey(connection, code, meta)
                }
                "keyboard.ime_action" -> {
                    val requested = when (value) {
                        "Current editor action" -> if (info.actionLabel != null) info.actionId else info.imeOptions and EditorInfo.IME_MASK_ACTION
                        "Go" -> EditorInfo.IME_ACTION_GO; "Search" -> EditorInfo.IME_ACTION_SEARCH
                        "Send" -> EditorInfo.IME_ACTION_SEND; "Next" -> EditorInfo.IME_ACTION_NEXT
                        "Done" -> EditorInfo.IME_ACTION_DONE; "Previous" -> EditorInfo.IME_ACTION_PREVIOUS
                        else -> error("Unknown editor action")
                    }
                    check(requested != EditorInfo.IME_ACTION_NONE && requested != EditorInfo.IME_ACTION_UNSPECIFIED) { "The editor did not advertise an action" }
                    connection.performEditorAction(requested)
                }
                else -> error("Unknown interaction")
            }
            return "Sent to focused editor"
        } finally { service.requestEditor(false) }
    }

    @RequiresApi(33)
    private fun sendKey(connection: AccessibilityInputConnection, code: Int, modifiers: Int) {
        val time = SystemClock.uptimeMillis()
        val down = KeyEvent(time, time, KeyEvent.ACTION_DOWN, code, 0, modifiers,
            KeyCharacterMap.VIRTUAL_KEYBOARD, 0, KeyEvent.FLAG_SOFT_KEYBOARD, InputDevice.SOURCE_KEYBOARD)
        connection.sendKeyEvent(down)
        connection.sendKeyEvent(KeyEvent.changeAction(down, KeyEvent.ACTION_UP))
    }

    @RequiresApi(33)
    private fun selectWord(connection: AccessibilityInputConnection, info: EditorInfo) {
        val variation = info.inputType and InputType.TYPE_MASK_VARIATION
        val klass = info.inputType and InputType.TYPE_MASK_CLASS
        check(!(klass == InputType.TYPE_CLASS_TEXT && variation in setOf(InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD, InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)) &&
            !(klass == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD)) {
            "Word selection is unavailable in protected fields"
        }
        // Only this explicit action inspects a bounded editor excerpt. No text leaves the method.
        val sample = connection.getSurroundingText(128, 128, 0) ?: error("Editor did not supply a text selection")
        check(sample.offset >= 0) { "Editor did not supply absolute text positions" }
        check(sample.text.length <= 512) { "Collapse the current selection before selecting a word" }
        val text = sample.text.toString()
        val caret = sample.selectionEnd.coerceIn(0, text.length)
        val i = if (caret < text.length && text[caret].isLetterOrDigit()) caret else caret - 1
        check(i in text.indices && text[i].isLetterOrDigit()) { "No word at the cursor" }
        val words = BreakIterator.getWordInstance().apply { setText(text) }
        val start = words.preceding(i + 1)
        val end = words.following(i)
        check(start >= 0 && end > start) { "Word bounds unavailable" }
        connection.setSelection(sample.offset + start, sample.offset + end)
    }
}
