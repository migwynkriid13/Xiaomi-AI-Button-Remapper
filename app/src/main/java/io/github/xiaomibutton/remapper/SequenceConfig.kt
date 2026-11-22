package io.github.xiaomibutton.remapper

import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

internal fun encodeActionSpec(action: ActionSpec): JSONObject = JSONObject().apply {
    put("kind", action.kind.name)
    put("value", action.value)
    put("extra", action.extra)
    if (action.label.isNotEmpty()) put("label", action.label)
}

internal fun decodeActionSpec(item: JSONObject) = ActionSpec(ActionKind.valueOf(item.get("kind") as String),
    item.get("value") as String, item.get("extra") as String, if (item.has("label")) item.get("label") as String else "")

internal data class SequenceStep(val action: ActionSpec, val delayMs: Int = 0)
internal data class SequenceConfig(val steps: List<SequenceStep>, val repeatCount: Int = 1) {
    private fun validate() {
        require(steps.size in 1..MAX_STEPS) { "Choose between 1 and $MAX_STEPS steps" }
        require(repeatCount in 1..MAX_REPEAT) { "Choose between 1 and $MAX_REPEAT runs" }
        for (step in steps) {
            require(step.action.kind != ActionKind.SEQUENCE) { "Sequences cannot contain other sequences" }
            require(step.delayMs in 0..MAX_DELAY) { "Step delay must be between 0 and $MAX_DELAY ms" }
            validateAction(step.action)
        }
        val totalDelay = steps.sumOf { it.delayMs } * repeatCount - steps.last().delayMs
        require(totalDelay <= 60_000) { "Total time between actions must be at most one minute" }
    }

    fun toAction(): ActionSpec {
        validate()
        val records = JSONArray()
        steps.forEach { step -> records.put(JSONObject().put("action", encodeActionSpec(step.action)).put("delayMs", step.delayMs)) }
        val raw = JSONObject().put("version", 1).put("repeat", repeatCount).put("steps", records).toString()
        require(raw.length <= MAX_LENGTH) { "Sequence is too large to save" }
        return ActionSpec(ActionKind.SEQUENCE, raw)
    }

    suspend fun run(perform: suspend (ActionSpec) -> String, wait: suspend (Long) -> Unit = { delay(it) }): String {
        var result = ""
        for (run in 1..repeatCount) {
            for ((i, step) in steps.withIndex()) {
                result = perform(step.action)
                check(!result.startsWith("Error:")) { "Step ${i + 1}: $result" }
                if (step.delayMs > 0 && (i < steps.lastIndex || run < repeatCount)) wait(step.delayMs.toLong())
            }
        }
        return "Completed ${steps.size * repeatCount} actions · $result"
    }

    companion object {
        const val MAX_LENGTH = 16_384
        const val MAX_STEPS = 12
        const val MAX_REPEAT = 5
        const val MAX_DELAY = 10_000

        fun fromAction(action: ActionSpec): SequenceConfig {
            require(action.kind == ActionKind.SEQUENCE && action.value.length <= MAX_LENGTH &&
                action.extra.isEmpty() && action.label.isEmpty()) { "Invalid sequence" }
            val parser = JSONTokener(action.value)
            val root = parser.nextValue() as? JSONObject ?: error("Expected a sequence object")
            require(parser.nextClean() == 0.toChar() && root.get("version") == 1) { "Invalid sequence format" }
            val repeats = root.get("repeat") as? Int ?: error("Invalid sequence repeat count")
            val items = root.getJSONArray("steps")
            require(items.length() in 1..MAX_STEPS) { "Invalid sequence length" }
            val steps = List(items.length()) { i ->
                val item = items.getJSONObject(i)
                val child = decodeActionSpec(item.getJSONObject("action"))
                val delayMs = item.get("delayMs") as? Int ?: error("Invalid step delay")
                SequenceStep(child, delayMs)
            }
            return SequenceConfig(steps, repeats).also { it.validate() }
        }
    }
}
