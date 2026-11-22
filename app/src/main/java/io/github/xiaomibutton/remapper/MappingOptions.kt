package io.github.xiaomibutton.remapper

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@Composable
internal fun MappingOptions(config: AppConfig, change: ((AppConfig) -> AppConfig) -> Unit) {
    val context = LocalContext.current
    val icons = remember { PickerIconCache() }
    var conditionSlot by rememberSaveable { mutableStateOf<GestureSlot?>(null) }
    var choosingApp by rememberSaveable { mutableStateOf(false) }
    var profile by rememberSaveable { mutableStateOf<String?>(null) }
    var editing by rememberSaveable { mutableStateOf<GestureSlot?>(null) }
    fun appName(pkg: String) = actionLabel(ActionSpec(ActionKind.APP, pkg), context)
    GroupTitle("When actions run")
    Panel {
        for (slot in GestureSlot.entries) SettingRow(slot.title, (config.conditions[slot] ?: TriggerCondition.ALWAYS).title,
            { conditionSlot = slot })
        SettingRow("Also run Xiaomi's action", "Pass through the AI button while running custom actions",
            { change { it.copy(keepOriginalAction = !it.keepOriginalAction) } }, checked = config.keepOriginalAction,
            trailing = { Switch(config.keepOriginalAction, onCheckedChange = null) })
    }
    GroupTitle("App profiles")
    Panel {
        for ((pkg, overrides) in config.profiles) {
            SettingRow(appName(pkg), if (overrides.isEmpty()) "Uses every default action" else "${overrides.size} gesture overrides",
                { profile = pkg }, trailing = { MappedActionIcon(ActionSpec(ActionKind.APP, pkg), icons) })
        }
        if (config.profiles.size < ConfigJson.MAX_PROFILES) SettingRow("Add app profile",
            "Override gestures when a chosen app is in front", { choosingApp = true })
    }

    conditionSlot?.let { slot ->
        AlertDialog(onDismissRequest = { conditionSlot = null }, title = { Text(slot.title) }, text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                for (condition in TriggerCondition.entries) ChoiceRow(condition.title, "", {
                    change { current ->
                        current.copy(conditions = if (condition == TriggerCondition.ALWAYS) current.conditions - slot
                        else current.conditions + (slot to condition))
                    }
                    conditionSlot = null
                }) {
                    RadioButton(selected = (config.conditions[slot] ?: TriggerCondition.ALWAYS) == condition, onClick = null)
                }
            }
        }, confirmButton = { TextButton(onClick = { conditionSlot = null }) { Text("Cancel") } })
    }

    if (choosingApp) AlertDialog(onDismissRequest = { choosingApp = false }, title = { Text("Choose an app for the profile") }, text = {
        Column(Modifier.heightIn(max = 400.dp)) {
            ChoiceList(ActionKind.APP, icons) { choice ->
                val pkg = choice.action?.value ?: return@ChoiceList
                change { current ->
                    if (pkg in current.profiles || current.profiles.size >= ConfigJson.MAX_PROFILES) current
                    else current.copy(profiles = current.profiles + (pkg to emptyMap()))
                }
                profile = pkg
                choosingApp = false
            }
        }
    }, confirmButton = { TextButton(onClick = { choosingApp = false }) { Text("Cancel") } })

    profile?.let { pkg ->
        val overrides = config.profiles[pkg] ?: return@let
        val slot = editing
        if (slot == null) AlertDialog(
            onDismissRequest = { profile = null }, title = { Text(appName(pkg)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    for (gesture in GestureSlot.entries) {
                        val override = overrides[gesture]
                        SettingRow(gesture.title, override?.let { actionLabel(it, context) }
                            ?: "Default · " + actionLabel(config.action(gesture), context), { editing = gesture })
                        if (override != null) TextButton(onClick = {
                            change { it.withProfileAction(pkg, gesture, null) }
                        }) { Text("Use default for " + gesture.title.lowercase()) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { profile = null }) { Text("Done") } },
            dismissButton = { TextButton(onClick = {
                profile = null
                change { it.copy(profiles = it.profiles - pkg) }
            }) { Text("Remove profile") } },
        ) else ActionPicker(slot, overrides[slot] ?: config.action(slot), { editing = null }) { action ->
            change { current -> if (pkg in current.profiles) current.withProfileAction(pkg, slot, action) else current }
            editing = null
        }
    }
}
