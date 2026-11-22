package io.github.xiaomibutton.remapper

import android.provider.Settings
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ConfigJsonTest {
    @Test fun exportedConfigRoundTripsMappingsAndTimingButNeverEnablesRemapping() {
        val active = AppConfig(enabled = true, single = ActionSpec(ActionKind.APP, "org.example.viewer"),
            double = ActionSpec(ActionKind.SETTINGS, Settings.ACTION_WIFI_SETTINGS),
            longPress = ActionSpec(ActionKind.URL, "https://example.org/a?q=value#top"),
            triple = ActionSpec(ActionKind.SHELL, "input keyevent KEYCODE_MEDIA_NEXT"),
            longMs = 1000, doubleMs = 420, debounceMs = 15, haptics = true)
        val export = ConfigJson.encode(active, export = true)
        assertFalse(JSONObject(export).has("enabled"))
        assertEquals(active.copy(enabled = false), ConfigJson.decode(export, imported = true))
        assertEquals(active, ConfigJson.decode(ConfigJson.encode(active)))
    }

    @Test fun activeFlagInImportedFileIsIgnored() {
        val hostile = JSONObject(ConfigJson.encode(AppConfig())).put("enabled", true).toString()
        assertFalse(ConfigJson.decode(hostile, imported = true).enabled)
    }

    @Test fun outOfRangeTimingAndWrongTypesAreRejected() {
        val base = ConfigJson.encode(AppConfig())
        for ((key, value) in listOf("version" to 2, "format" to "other", "longMs" to 249,
                "doubleMs" to 801, "debounceMs" to -1, "longMs" to "450", "doubleMs" to 220.4, "haptics" to "false")) {
            assertThrows(Exception::class.java) { ConfigJson.decode(JSONObject(base).put(key, value).toString(), true) }
        }
        assertThrows(Exception::class.java) { ConfigJson.decode("garbage", true) }
        assertThrows(Exception::class.java) { ConfigJson.decode(" ".repeat(ConfigJson.MAX_BYTES + 1), true) }
    }

    @Test fun missingGestureOrUnknownActionCannotProducePartialImport() {
        val base = JSONObject(ConfigJson.encode(AppConfig()))
        base.getJSONObject("actions").getJSONObject("DOUBLE").put("kind", "NEW_KIND")
        assertThrows(Exception::class.java) { ConfigJson.decode(base.toString(), true) }
        base.getJSONObject("actions").remove("SINGLE")
        assertThrows(Exception::class.java) { ConfigJson.decode(base.toString(), true) }
    }

    @Test fun unsafeOrMalformedImportedActionsAreRejected() {
        val invalid = listOf(ActionSpec(ActionKind.SHELL, "input keyevent KEYCODE_MEDIA_NEXT; id"),
            ActionSpec(ActionKind.URL, "javascript:alert(1)"), ActionSpec(ActionKind.URL, "https:"),
            ActionSpec(ActionKind.SETTINGS, "android.intent.action.VIEW"),
            ActionSpec(ActionKind.APP, "-p org.example"), ActionSpec(ActionKind.APP, "a".repeat(256)),
            ActionSpec(ActionKind.SHORTCUT, "", "shortcut-id"), ActionSpec(ActionKind.INTENT, "intent:"),
            ActionSpec(ActionKind.NONE, "unexpected"), ActionSpec(ActionKind.URL, "https://example.org", "extra"),
            ActionSpec(ActionKind.URL, "https://example.org/\n"), ActionSpec(ActionKind.APP, "a".repeat(2049)))
        for (action in invalid) {
            val text = ConfigJson.encode(AppConfig(longPress = action), export = true)
            assertThrows(action.toString(), Exception::class.java) { ConfigJson.decode(text, imported = true) }
        }
    }

    @Test fun hostedShortcutKeepsPackageAndIdSeparate() {
        val config = AppConfig(double = ActionSpec(ActionKind.SHORTCUT, "org.example", "open/inbox"))
        assertEquals(config, ConfigJson.decode(ConfigJson.encode(config, export = true), imported = true))
    }
}

