package io.github.xiaomibutton.remapper

import android.content.Context
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import org.json.JSONObject
import org.json.JSONTokener
import java.io.IOException

enum class ActionKind(val title: String) {
    NONE("No action"), CAMERA("Open camera"), FLASHLIGHT("Toggle flashlight"), APP("Open application"),
    SHORTCUT("Launch shortcut"), SILENT("Toggle silent mode"), DND("Toggle Do Not Disturb"),
    URL("Open website"), INTENT("Launch Android intent"), SHELL("Run shell command"),
    SETTINGS("Open system setting"), CATALOG("Button action"), SEQUENCE("Action sequence")
}

data class ActionSpec(val kind: ActionKind, val value: String = "", val extra: String = "", val label: String = "")

enum class GestureSlot(val title: String) {
    SINGLE("Single press"), DOUBLE("Double press"), LONG("Long press"), TRIPLE("Triple press")
}

data class AppConfig(
    val enabled: Boolean = false,
    val single: ActionSpec = ActionSpec(ActionKind.CAMERA),
    val double: ActionSpec = ActionSpec(ActionKind.NONE),
    val longPress: ActionSpec = ActionSpec(ActionKind.NONE),
    val triple: ActionSpec = ActionSpec(ActionKind.NONE),
    val doubleMs: Int = 300,
    val longMs: Int = 600,
    val debounceMs: Int = 25,
    val haptics: Boolean = false
) {
    fun action(slot: GestureSlot) = when (slot) {
        GestureSlot.SINGLE -> single
        GestureSlot.DOUBLE -> double
        GestureSlot.LONG -> longPress
        GestureSlot.TRIPLE -> triple
    }

    fun withAction(slot: GestureSlot, action: ActionSpec) = when (slot) {
        GestureSlot.SINGLE -> copy(single = action)
        GestureSlot.DOUBLE -> copy(double = action)
        GestureSlot.LONG -> copy(longPress = action)
        GestureSlot.TRIPLE -> copy(triple = action)
    }
}

/** Shared application-scoped instance. Only the normal application process accesses this file. */
private val Context.settingsDataStore by preferencesDataStore(
    name = "remapper",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() }
)

class ConfigStore(context: Context) {
    private val store = context.applicationContext.settingsDataStore
    private val key = stringPreferencesKey("config_v1")
    val config: Flow<AppConfig> = store.data.map { preferences -> read(preferences[key]) }.catch { error ->
        if (error is IOException) emit(AppConfig()) else throw error
    }

    suspend fun update(transform: (AppConfig) -> AppConfig) {
        store.edit { preferences -> preferences[key] = ConfigJson.encode(transform(read(preferences[key]))) }
    }

    private fun read(raw: String?): AppConfig =
        raw?.let { runCatching { ConfigJson.decode(it) }.getOrNull() } ?: AppConfig()
}

object ConfigJson {
    const val MAX_BYTES = 64 * 1024
    val doubleRange = 120..800
    val longRange = 250..1800
    val debounceRange = 0..75

    fun encode(config: AppConfig, export: Boolean = false): String {
        val actions = JSONObject()
        GestureSlot.entries.forEach { slot ->
            val action = config.action(slot)
            actions.put(slot.name, encodeActionSpec(action))
        }
        return JSONObject().apply {
            put("format", "xiaomi-ai-button-remapper")
            put("version", 1)
            if (!export) put("enabled", config.enabled)
            put("actions", actions)
            put("doubleMs", config.doubleMs)
            put("longMs", config.longMs)
            put("debounceMs", config.debounceMs)
            put("haptics", config.haptics)
        }.toString(2).also {
            require(it.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Configuration exceeds 64 KiB" }
        }
    }

    fun decode(raw: String, imported: Boolean = false): AppConfig {
        require(raw.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Configuration exceeds 64 KiB" }
        val parser = JSONTokener(raw)
        val obj = parser.nextValue() as? JSONObject ?: error("Expected a JSON configuration object")
        require(parser.nextClean() == 0.toChar()) { "Unexpected content after configuration" }
        require(obj.get("format") == "xiaomi-ai-button-remapper" && obj.get("version") == 1) {
            "Unsupported configuration format or version"
        }
        fun intIn(key: String, range: IntRange): Int = (obj.get(key) as? Int ?: error("Invalid timing for " + key)).also {
            require(it in range) { "Invalid timing for " + key }
        }
        var config = AppConfig(
            enabled = !imported && obj.optBoolean("enabled", false),
            doubleMs = intIn("doubleMs", doubleRange),
            longMs = intIn("longMs", longRange),
            debounceMs = intIn("debounceMs", debounceRange),
            haptics = obj.get("haptics") as? Boolean ?: error("Invalid haptic setting")
        )
        val actions = obj.getJSONObject("actions")
        for (slot in GestureSlot.entries) {
            val item = actions.getJSONObject(slot.name)
            val action = decodeActionSpec(item)
            validateAction(action)
            config = config.withAction(slot, action)
        }
        return config
    }
}

