package io.github.xiaomibutton.remapper

import io.github.xiaomibutton.remapper.runtime.ExpertDeviceActions
import org.junit.Assert.*
import org.junit.Test

class CatalogContractTest {
    @Test fun actionIdsAreUniqueAndScreenshotActionsHaveHandlers() {
        val actions = ActionLibrary.actions
        assertEquals(actions.size, actions.map { it.id }.distinct().size)
        for (id in listOf("nav.home","nav.last","flash.on","flash.strength","display.brighter",
                "display.night.toggle","device.screenshot","apps.clear_recent","apps.stop","input.tap")) {
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

    @Test fun scopedExpertCommandsUseFixedArgumentArraysAndRejectAmbiguousState() {
        val calls = mutableListOf<List<String>>()
        val result = ExpertDeviceActions.run("radio.nfc.toggle", "", "0") { args ->
            calls += args
            if (args.last() == "status") "Nfc is enabled" else "Completed"
        }
        assertEquals("Completed", result)
        assertEquals(listOf("/system/bin/cmd","nfc","disable-nfc"), calls.last())
        assertTrue(ExpertDeviceActions.run("radio.nfc.toggle", "", "0") { "unknown state" }.startsWith("Error:"))
        assertThrows(IllegalArgumentException::class.java) {
            ExpertDeviceActions.run("apps.stop", "com.example;ls", "0") { error("must refuse before executing") }
        }
        val listing = """
            taskId=42: com.example/.Main bounds=[0,0][100,200] userId=0 visible=true
            taskId=43: com.example.peer/.Main bounds=[] userId=0 visible=false
            taskId=44: com.example/.Work bounds=[] userId=10 visible=false
        """.trimIndent()
        assertEquals(listOf("42"), ExpertDeviceActions.tasksForPackage(listing,"com.example","0"))
    }
}
