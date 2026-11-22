package io.github.xiaomibutton.remapper

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.os.Process
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.xiaomibutton.remapper.runtime.PermittedCommands
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal fun actionLabel(action: ActionSpec, context: Context, detail: Boolean = false): String = when (action.kind) {
    ActionKind.APP -> runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(action.value, 0)).toString()
    }.getOrDefault(action.value)
    ActionKind.SHORTCUT -> if (action.extra.isNotEmpty()) action.extra else if (detail) action.value else "App shortcut"
    ActionKind.SETTINGS -> systemPages[action.value] ?: action.value
    ActionKind.URL, ActionKind.INTENT -> action.value
    ActionKind.SHELL -> if (detail) action.value else PermittedCommands.options[action.value] ?: action.value
    else -> action.kind.title
}

private data class Choice(val title: String, val detail: String, val action: ActionSpec? = null,
    val provider: ComponentName? = null)

@Suppress("DEPRECATION")
private fun loadChoices(context: Context, kind: ActionKind): List<Choice> {
    val pm = context.packageManager
    fun providers(intent: Intent) = pm.queryIntentActivities(intent, 0)
        .filter { it.activityInfo.exported && it.activityInfo.enabled }
    val choices = when (kind) {
        ActionKind.APP -> providers(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER))
            .distinctBy { it.activityInfo.packageName }.map {
                val pkg = it.activityInfo.packageName
                Choice(it.loadLabel(pm).toString(), pkg, ActionSpec(kind, pkg))
            }
        ActionKind.SHORTCUT -> buildList {
            addAll(providers(Intent(Intent.ACTION_CREATE_SHORTCUT)).map {
                Choice(it.loadLabel(pm).toString(), it.activityInfo.packageName, provider =
                    ComponentName(it.activityInfo.packageName, it.activityInfo.name))
            })
            val launcher = context.getSystemService(LauncherApps::class.java)
            if (launcher.hasShortcutHostPermission()) {
                val flags = LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED or
                    LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST
                val query = LauncherApps.ShortcutQuery().setQueryFlags(flags)
                launcher.getShortcuts(query, Process.myUserHandle()).orEmpty().filter { it.isEnabled }.forEach {
                    add(Choice(it.shortLabel?.toString() ?: it.id, it.`package`, ActionSpec(kind, it.`package`, it.id)))
                }
            }
        }
        ActionKind.SETTINGS -> systemPages.map { (id, label) -> Choice(label, id, ActionSpec(kind, id)) }
        ActionKind.SHELL -> PermittedCommands.options.map { (cmd, label) -> Choice(label, cmd, ActionSpec(kind, cmd)) }
        else -> emptyList()
    }
    return choices.sortedBy { it.title.lowercase() }
}

@Composable
internal fun ActionPicker(slot: GestureSlot, current: ActionSpec, dismiss: () -> Unit, save: (ActionSpec) -> Unit) {
    val context = LocalContext.current
    var categoryName by rememberSaveable(slot.name) { mutableStateOf<String?>(null) }
    val category = categoryName?.let(ActionKind::valueOf)
    var query by rememberSaveable(categoryName) { mutableStateOf("") }
    var value by rememberSaveable(categoryName) { mutableStateOf(if (category == current.kind) current.value else "") }
    var problem by rememberSaveable { mutableStateOf<String?>(null) }
    val shortcutCreator = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            runCatching {
                @Suppress("DEPRECATION")
                val intent = result.data?.getParcelableExtra<Intent>(Intent.EXTRA_SHORTCUT_INTENT)
                    ?: error("The provider returned no shortcut")
                val action = ActionSpec(ActionKind.SHORTCUT, intent.toUri(Intent.URI_INTENT_SCHEME))
                validateAction(action)
                save(action)
            }.onFailure { problem = it.localizedMessage ?: "The shortcut could not be read" }
        }
    }

    fun goBack() { if (categoryName == null) dismiss() else { categoryName = null; problem = null } }
    Dialog(onDismissRequest = ::goBack, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.widthIn(max = 540.dp).fillMaxWidth(0.94f).imePadding().heightIn(max = 640.dp)
            .fillMaxHeight(0.85f), shape = MaterialTheme.shapes.extraLarge) {
            Column(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxWidth().padding(start = 22.dp, end = 12.dp, top = 14.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(slot.title, Modifier.padding(top = 12.dp), style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary)
                        TextButton(onClick = ::goBack) { Text(if (category == null) "Close" else "Back") }
                    }
                    Text(category?.title ?: "Choose action", style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold)
                }
                problem?.let { Text(it, Modifier.padding(horizontal = 22.dp, vertical = 6.dp),
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                if (category == null) {
                    LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
                        items(listOf(ActionKind.CAMERA, ActionKind.FLASHLIGHT, ActionKind.APP, ActionKind.SHORTCUT,
                            ActionKind.SETTINGS, ActionKind.SILENT, ActionKind.DND, ActionKind.URL, ActionKind.INTENT,
                            ActionKind.SHELL, ActionKind.NONE)) { kind ->
                            val detail = when (kind) {
                                ActionKind.NONE -> if (slot == GestureSlot.SINGLE) "Consume the button press" else "Turn off this gesture"
                                ActionKind.DND -> "Requires Do Not Disturb access"
                                ActionKind.SHELL -> "Choose a permitted command"
                                else -> null
                            }
                            SettingRow(kind.title, detail, onClick = {
                                if (kind in listOf(ActionKind.APP, ActionKind.SHORTCUT, ActionKind.SETTINGS,
                                        ActionKind.URL, ActionKind.INTENT, ActionKind.SHELL)) {
                                    categoryName = kind.name
                                } else save(ActionSpec(kind))
                            })
                        }
                    }
                } else if (category == ActionKind.URL || category == ActionKind.INTENT) {
                    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(22.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        val website = category == ActionKind.URL
                        OutlinedTextField(value, { value = it; problem = null }, modifier = Modifier.fillMaxWidth(),
                            label = { Text(if (website) "Website address" else "Intent URI") }, maxLines = 5)
                        QuietText(if (website) "Example: https://example.org" else
                            "Example: intent:#Intent;action=android.settings.DISPLAY_SETTINGS;end")
                        Button(onClick = {
                            val action = ActionSpec(category, value.trim())
                            runCatching { validateAction(action); save(action) }
                                .onFailure { problem = it.localizedMessage ?: "Invalid action" }
                        }, enabled = value.isNotBlank()) { Text("Save action") }
                    }
                } else {
                    val choices by produceState<List<Choice>?>(null, category) {
                        runCatching { withContext(Dispatchers.IO) { loadChoices(context, category) } }
                            .onSuccess { this.value = it }
                            .onFailure { problem = it.localizedMessage; this.value = emptyList() }
                    }
                    OutlinedTextField(query, { query = it }, modifier = Modifier.fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 8.dp), label = { Text("Search") }, singleLine = true)
                    val available = choices
                    if (available == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                    else {
                        val filtered = available.filter { it.title.contains(query, true) || it.detail.contains(query, true) }
                        if (filtered.isEmpty()) QuietText(
                            if (category == ActionKind.SHORTCUT && available.isEmpty())
                                "No shortcut providers are available. Android limits launcher shortcuts to shortcut hosts. Apps can also publish a shortcut creator."
                            else "No matching entries", Modifier.padding(22.dp))
                        LazyColumn(modifier = Modifier.weight(1f, fill = false), contentPadding = PaddingValues(bottom = 16.dp)) {
                            items(filtered) { item ->
                                Column(Modifier.fillMaxWidth().clickable {
                                    if (item.action != null) save(item.action)
                                    else runCatching { shortcutCreator.launch(Intent(Intent.ACTION_CREATE_SHORTCUT)
                                        .setComponent(item.provider)) }.onFailure { problem = it.localizedMessage }
                                }.padding(horizontal = 22.dp, vertical = 12.dp)) {
                                    Text(item.title, style = MaterialTheme.typography.titleSmall)
                                    QuietText(item.detail)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

