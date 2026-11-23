package io.github.xiaomibutton.remapper.backscreen.dashboard

import android.content.Context
import androidx.compose.runtime.*
import io.github.xiaomibutton.remapper.*
import io.github.xiaomibutton.remapper.R
import org.json.JSONArray
import org.json.JSONObject

internal enum class Page(val title: String, val icon: Int) {
    HOME("Home", R.drawable.action_home),
    MUSIC("Now playing", R.drawable.action_music_note),
    LIVE("Live activities", R.drawable.action_view_agenda),
    CONTROLS("Control Centre", R.drawable.action_tune),
    APPS("Launcher", R.drawable.action_apps),
    SHARED("Pinned notes and photos", R.drawable.action_open_with),
    INBOX("Notifications", R.drawable.action_notifications_off),
    CONNECTIONS("Connected cards", R.drawable.action_link),
    GAME("Tap garden", R.drawable.action_touch_app),
}

internal class DashboardStore(val context: Context) {
    val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private val changes = mutableIntStateOf(0)
    private val listener =
        android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            changes.intValue++
        }
    private val savedPins = context.getSharedPreferences("rear_pinned_items", Context.MODE_PRIVATE)

    private fun storage(key: String) =
        if (key == "shares" || key.startsWith("note.")) savedPins else prefs

    init {
        prefs.registerOnSharedPreferenceChangeListener(listener)
        savedPins.registerOnSharedPreferenceChangeListener(listener)
    }

    fun flag(key: String, fallback: Boolean = false): Boolean {
        changes.intValue
        return prefs.getBoolean(key, fallback)
    }

    fun value(key: String, fallback: String = ""): String {
        changes.intValue
        return storage(key).getString(key, fallback) ?: fallback
    }

    fun remove(key: String) = storage(key).edit().remove(key).apply()

    fun set(key: String, value: String) = storage(key).edit().putString(key, value).apply()

    fun set(key: String, value: Boolean) = prefs.edit().putBoolean(key, value).apply()

    fun strings(key: String, defaults: List<String> = emptyList()): List<String> {
        changes.intValue
        val raw = storage(key).getString(key, null) ?: return defaults
        return runCatching { JSONArray(raw).let { a -> List(a.length()) { a.getString(it) } } }
            .getOrDefault(defaults)
    }

    fun save(key: String, items: List<String>) = set(key, JSONArray(items).toString())

    fun pages() =
        strings("pages", Page.entries.map { it.name })
            .mapNotNull { name -> Page.entries.find { it.name == name } }
            .distinct()

    fun cards() = strings("cards", CARD_NAMES.take(4))

    fun action(key: String) = runCatching { decodeActionSpec(JSONObject(value(key))) }.getOrNull()

    fun action(key: String, spec: ActionSpec) = set(key, encodeActionSpec(spec).toString())

    fun pins() = strings("tiles")

    companion object {
        const val FILE = "rear_dashboard"
        val CARD_NAMES =
            listOf(
                "Battery",
                "Weather",
                "Next alarm",
                "Audio output",
                "Calendar",
                "Accessory battery",
                "Now playing",
                "Connected cards",
            )
    }
}
