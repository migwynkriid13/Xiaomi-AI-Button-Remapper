package io.github.xiaomibutton.remapper

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SequenceConfigTest {
    private val camera = ActionSpec(ActionKind.CAMERA)
    private val light = ActionSpec(ActionKind.FLASHLIGHT)

    @Test fun v1ExportImportsStoredSequenceAndPreservesOrdinaryActions() {
        val sequence = SequenceConfig(listOf(SequenceStep(camera, 100), SequenceStep(light)), 2)
        val mixed = AppConfig(single = sequence.toAction(), double = camera)
        val restored = ConfigJson.decode(ConfigJson.encode(mixed, export = true), imported = true)
        assertEquals(mixed, restored)
        assertEquals(sequence, SequenceConfig.fromAction(restored.single))
    }

    @Test fun runsInOrderAndDelaysOnlyBetweenSteps() = runBlocking {
        val log = mutableListOf<String>()
        val sequence = SequenceConfig(listOf(SequenceStep(camera, 50), SequenceStep(light, 80)), 2)
        sequence.run({ action -> action.kind.name.also(log::add) }, { log.add(it.toString()) })
        assertEquals(listOf("CAMERA", "50", "FLASHLIGHT", "80", "CAMERA", "50", "FLASHLIGHT"), log)
    }

    @Test fun rejectsRecursionAndExcessiveWork() {
        val valid = SequenceConfig(listOf(SequenceStep(light))).toAction()
        val nestedRaw = JSONObject().put("version", 1).put("repeat", 1).put("steps", JSONArray()
            .put(JSONObject().put("action", encodeActionSpec(valid)).put("delayMs", 0))).toString()
        assertThrows(IllegalArgumentException::class.java) { SequenceConfig.fromAction(ActionSpec(ActionKind.SEQUENCE, nestedRaw)) }
        assertThrows(IllegalArgumentException::class.java) { SequenceConfig(emptyList()).toAction() }
        assertThrows(IllegalArgumentException::class.java) { SequenceConfig(List(13) { SequenceStep(light) }).toAction() }
        assertThrows(IllegalArgumentException::class.java) { SequenceConfig(listOf(SequenceStep(light)), 6).toAction() }
        assertThrows(IllegalArgumentException::class.java) { SequenceConfig(List(10) { SequenceStep(light, 10_000) }).toAction() }
    }

    @Test fun failingStepStopsSequenceBeforeLaterSteps() {
        val seen = mutableListOf<ActionSpec>()
        val sequence = SequenceConfig(listOf(SequenceStep(camera), SequenceStep(light)))
        assertThrows(IllegalStateException::class.java) {
            runBlocking { sequence.run({ action -> seen += action; "Error: unavailable" }) }
        }
        assertEquals(listOf(camera), seen)
    }
}
