package io.github.xiaomibutton.remapper

import org.junit.Assert.*
import org.junit.Test

class MappingOptionsTest {
    @Test fun profilesConditionsAndPassThroughRoundTripWithoutExportingDiagnosticsPreference() {
        val pkg = "com.example.reader"
        val screenshot = ActionSpec(ActionKind.CATALOG, "device.screenshot")
        val config = AppConfig(enabled = true, showDiagnostics = true, keepOriginalAction = true,
            conditions = mapOf(GestureSlot.DOUBLE to TriggerCondition.MEDIA_PLAYING),
            profiles = mapOf(pkg to mapOf(GestureSlot.SINGLE to screenshot)))
        assertEquals(config, ConfigJson.decode(ConfigJson.encode(config)))
        val imported = ConfigJson.decode(ConfigJson.encode(config, export = true), imported = true)
        assertEquals(config.copy(enabled = false, showDiagnostics = false), imported)
        assertEquals(screenshot, imported.profiles.getValue(pkg).getValue(GestureSlot.SINGLE))
        assertEquals(ActionKind.CAMERA, imported.action(GestureSlot.SINGLE).kind)
        assertTrue(imported.withProfileAction(pkg, GestureSlot.SINGLE, null).profiles.getValue(pkg).isEmpty())
    }

    @Test fun oldConfigurationsDefaultToNoProfilesConditionsOrDiagnostics() {
        val previous = ConfigJson.encode(AppConfig())
        assertFalse(previous.contains("profiles"))
        val config = ConfigJson.decode(previous)
        assertTrue(config.profiles.isEmpty())
        assertTrue(config.conditions.isEmpty())
        assertFalse(config.showDiagnostics)
        assertFalse(config.keepOriginalAction)
    }
}
