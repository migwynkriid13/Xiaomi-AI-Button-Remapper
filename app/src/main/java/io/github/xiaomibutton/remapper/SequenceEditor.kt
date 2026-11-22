package io.github.xiaomibutton.remapper

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import org.json.JSONObject

private val stepSaver = Saver<List<SequenceStep>, ArrayList<String>>(
    save = { steps -> ArrayList(steps.map { encodeActionSpec(it.action).put("delayMs", it.delayMs).toString() }) },
    restore = { cells -> cells.map { raw ->
        val item = JSONObject(raw)
        SequenceStep(decodeActionSpec(item), item.getInt("delayMs"))
    } }
)

@Composable
internal fun SequenceEditor(slot: GestureSlot, current: ActionSpec, save: (ActionSpec) -> Unit) {
    val context = LocalContext.current
    val icons = remember { PickerIconCache() }
    val currentSequence = remember(current) {
        if (current.kind == ActionKind.SEQUENCE) SequenceConfig.fromAction(current) else
            SequenceConfig(if (current.kind == ActionKind.NONE) emptyList() else listOf(SequenceStep(current)))
    }
    var steps by rememberSaveable(stateSaver = stepSaver) { mutableStateOf(currentSequence.steps) }
    var runs by rememberSaveable { mutableIntStateOf(currentSequence.repeatCount) }
    var editing by rememberSaveable { mutableStateOf<Int?>(null) }
    var delayIndex by rememberSaveable { mutableStateOf<Int?>(null) }
    var delayText by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    fun move(a: Int, b: Int) {
        steps = steps.toMutableList().apply { add(b, removeAt(a)) }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = ScreenGutter), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(steps) { index, step ->
                Panel {
                    SettingRow(
                        "Step ${index + 1}", actionLabel(step.action, context), { editing = index },
                        leading = { MappedActionIcon(step.action, icons) }
                    )
                    RowDivider()
                    FlowRow(Modifier.padding(horizontal = 8.dp, vertical = 3.dp)) {
                        TextButton(onClick = { delayText = step.delayMs.toString(); delayIndex = index }) {
                            Text("Delay ${step.delayMs} ms")
                        }
                        TextButton(onClick = { move(index, index - 1) }, enabled = index > 0) { Text("Earlier") }
                        TextButton(onClick = { move(index, index + 1) }, enabled = index < steps.lastIndex) { Text("Later") }
                        TextButton(onClick = { steps = steps.filterIndexed { i, _ -> i != index } }) { Text("Remove") }
                    }
                }
            }
            item {
                SecondaryButton("Add action", { editing = steps.size }, enabled = steps.size < SequenceConfig.MAX_STEPS)
            }
            item {
                Panel {
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text("Runs", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                        TextButton(onClick = { runs-- }, enabled = runs > 1,
                            modifier = Modifier.semantics { contentDescription = "Fewer runs" }) { Text("−") }
                        Text(runs.toString(), style = MaterialTheme.typography.labelLarge)
                        TextButton(onClick = { runs++ }, enabled = runs < SequenceConfig.MAX_REPEAT,
                            modifier = Modifier.semantics { contentDescription = "More runs" }) { Text("+") }
                    }
                }
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        PrimaryButton("Save sequence", {
            runCatching { SequenceConfig(steps, runs).toAction() }.onSuccess(save).onFailure { error = it.message }
        }, Modifier.fillMaxWidth().padding(bottom = 12.dp), enabled = steps.isNotEmpty())
    }
    editing?.let { index ->
        ActionPicker(slot, steps.getOrNull(index)?.action ?: ActionSpec(ActionKind.NONE), dismiss = { editing = null }, allowSequence = false) { action ->
            steps = steps.toMutableList().apply {
                if (index == size) add(SequenceStep(action)) else set(index, get(index).copy(action = action))
            }
            error = null
            editing = null
        }
    }
    delayIndex?.let { index ->
        val parsed = delayText.toIntOrNull()?.takeIf { it in 0..SequenceConfig.MAX_DELAY }
        RemapperDialog(onDismissRequest = { delayIndex = null }, title = { Text("Delay before next action") },
            text = { EditorField(delayText, { delayText = it }, singleLine = true,
                label = { Text("Milliseconds (0–10000)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)) },
            confirmButton = { PrimaryButton("Apply", enabled = parsed != null, onClick = {
                if (parsed != null) steps = steps.toMutableList().apply { set(index, get(index).copy(delayMs = parsed)) }
                error = null
                delayIndex = null
            }) }, dismissButton = { TextButton(onClick = { delayIndex = null }) { Text("Cancel") } })
    }
}
