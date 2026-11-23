package io.github.xiaomibutton.remapper

import org.junit.Assert.*
import org.junit.Test

class CatalogContractTest {
    @Test fun actionIdsAreUniqueAndScreenshotActionsHaveHandlers() {
        val actions = ActionLibrary.actions
        assertEquals(actions.size, actions.map { it.id }.distinct().size)
        for (id in listOf("nav.home","nav.last","flash.on","flash.strength","display.brighter",
                "display.night.toggle","device.screenshot","input.tap","backscreen.quick_switch",
                "backscreen.wallpaper.toggle")) {
            assertNotNull(id, ActionLibrary.find(id))
        }
        assertTrue(actions.count { it.group == "Media" } >= 20)
        println("Catalog contains ${actions.size} named actions in ${actions.map { it.group }.distinct().size} groups")
    }

    @Test fun catalogMappingsRoundTripAndInvalidImportsAreRejected() {
        val action = ActionSpec(ActionKind.CATALOG,"display.brighter","8","Brightness +8%")
        val original = AppConfig(enabled = true, single = action)
        val serialized = ConfigJson.encode(original, export = true)
        val restored = ConfigJson.decode(serialized, imported = true)
        assertEquals(action, restored.single)
        assertFalse(restored.enabled)
        assertThrows(IllegalArgumentException::class.java) { validateAction(action.copy(extra = "101")) }
        assertThrows(IllegalArgumentException::class.java) { validateAction(action.copy(value = "unknown.action")) }
        assertThrows(IllegalArgumentException::class.java) { validateAction(action.copy(value = "maps.trigger", extra = "123")) }
    }

    @Test fun retiredActionsStayReadableButNeverAppearInTheCatalog() {
        val forceStop = ActionSpec(ActionKind.CATALOG, "apps.stop", "com.example")
        val shell = ActionSpec(ActionKind.SHELL, "input keyevent KEYCODE_MEDIA_NEXT")
        val old = AppConfig(single = forceStop, longPress = shell)
        val restored = ConfigJson.decode(ConfigJson.encode(old), imported = true)
        assertEquals(forceStop, restored.single)
        assertEquals(shell, restored.longPress)
        for (id in listOf("device.power", "device.reboot", "device.shutdown", "apps.stop", "apps.clear_recent")) {
            assertTrue(id, ActionLibrary.isRetired(id))
            assertFalse(id, ActionLibrary.actions.any { it.id == id })
        }
        assertThrows(IllegalArgumentException::class.java) { validateAction(forceStop.copy(extra = "com.example;ls")) }
    }
}
