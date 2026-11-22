package io.github.xiaomibutton.remapper

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun SearchField(value: String, onChange: (String) -> Unit, label: String = "Search") {
    OutlinedTextField(value, onChange, modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 6.dp),
        label = { Text(label) }, singleLine = true,
        leadingIcon = { Icon(painterResource(R.drawable.action_search), contentDescription = null) })
}

@Composable
internal fun ChoiceRow(title: String, detail: String, onClick: () -> Unit, leading: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).heightIn(min = 54.dp)
        .padding(horizontal = 18.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        leading()
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            if (detail.isNotBlank()) QuietText(detail)
        }
    }
}

@Composable
internal fun ChoiceList(kind: ActionKind, icons: PickerIconCache, onPick: (Choice) -> Unit) {
    val context = LocalContext.current
    var query by rememberSaveable(kind) { mutableStateOf("") }
    var failure by remember { mutableStateOf<String?>(null) }
    val choices by produceState<List<Choice>?>(null, kind) {
        try { value = withContext(Dispatchers.IO) { loadChoices(context, kind) } }
        catch (e: Exception) { failure = e.localizedMessage; value = emptyList() }
    }
    SearchField(query, { query = it })
    val available = choices
    if (available == null) LinearProgressIndicator(Modifier.fillMaxWidth())
    else {
        val filtered = available.filter { it.title.contains(query, true) || it.detail.contains(query, true) }
        if (filtered.isEmpty()) QuietText(failure ?: when {
            query.isNotEmpty() -> "No matching entries"
            kind == ActionKind.SHORTCUT -> "No shortcut creators are installed. Android also exposes launcher shortcuts to authorized shortcut hosts."
            kind == ActionKind.SETTINGS -> "This device publishes no supported Settings destinations."
            else -> "No entries available"
        }, Modifier.padding(18.dp))
        LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
            items(filtered, key = { it.key }) { item ->
                ChoiceRow(item.title, item.detail, { onPick(item) }) {
                    if (item.icon != null) PickerIcon(item.icon, icons) else ActionGlyph(glyphFor(kind))
                }
            }
        }
    }
}

private data class Entry(val kind: ActionKind?, val catalog: CatalogAction?, val title: String, val group: String, val detail: String = "") {
    val id get() = catalog?.id ?: kind!!.name
}

@Composable
internal fun ActionBrowser(slot: GestureSlot, icons: PickerIconCache, allowSequence: Boolean, onBasic: (ActionKind) -> Unit,
    onCatalog: (CatalogAction) -> Unit, onChoice: (Choice) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf("All") }
    val context = LocalContext.current
    val entries = remember(slot, allowSequence) {
        val groups = linkedMapOf("Apps" to listOf(ActionKind.APP, ActionKind.SHORTCUT, ActionKind.URL, ActionKind.SETTINGS),
            "Button actions" to listOf(ActionKind.CAMERA, ActionKind.FLASHLIGHT, ActionKind.SILENT, ActionKind.DND, ActionKind.NONE),
            "Advanced" to buildList {
                if (allowSequence) add(ActionKind.SEQUENCE)
                add(ActionKind.INTENT)
            })
        groups.flatMap { (group, kinds) -> kinds.map { kind ->
            val detail = when (kind) {
                ActionKind.DND -> "Requires Do Not Disturb access"
                ActionKind.NONE -> if (slot == GestureSlot.SINGLE) "Consume the button press" else "Turn off this gesture"
                else -> ""
            }
            Entry(kind, null, kind.title, group, detail)
        } } + ActionLibrary.actions.map { Entry(null, it, it.title, it.group, it.requirement) }
    }
    val groups = remember(entries) { listOf("All") + entries.map { it.group }.distinct() }
    SearchField(query, { query = it }, "Search actions, apps and shortcuts")
    LazyRow(contentPadding = PaddingValues(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items(groups, key = { it }) { group ->
            FilterChip(selected = group == filter, onClick = { filter = group }, label = { Text(group) })
        }
    }
    val search = query.trim()
    val shown = remember(entries, search, filter) { entries.filter {
        (filter == "All" || filter == it.group) && (search.isEmpty() || it.title.contains(search, true) ||
            it.group.contains(search, true) || it.catalog?.id?.contains(search, true) == true)
    }.groupBy { it.group } }
    val includeInstalled = search.isNotEmpty() && filter in setOf("All", "Apps", "Settings")
    val installed = if (includeInstalled) {
        val choices by produceState<List<Pair<ActionKind, Choice>>>(emptyList(), context) {
            value = withContext(Dispatchers.IO) {
                listOf(ActionKind.APP, ActionKind.SHORTCUT, ActionKind.SETTINGS).flatMap { kind ->
                    try { loadChoices(context, kind).map { kind to it } } catch (_: Exception) { emptyList() }
                }
            }
        }
        choices.filter { (kind, choice) -> (filter != "Settings" || kind == ActionKind.SETTINGS) &&
            (choice.title.contains(search, true) || choice.detail.contains(search, true)) }
    } else emptyList()
    key(filter, search) {
        LazyColumn(contentPadding = PaddingValues(bottom = 12.dp)) {
            shown.forEach { (group, list) ->
                item(key = "group/" + group) { GroupLabel(group) }
                items(list, key = { "action/" + it.id }) { item ->
                    ChoiceRow(item.title, item.detail, {
                        if (item.catalog != null) onCatalog(item.catalog) else onBasic(item.kind!!)
                    }) { ActionGlyph(glyphFor(item.kind, item.catalog)) }
                }
            }
            installed.groupBy { it.first }.forEach { (kind, list) ->
                val heading = when (kind) { ActionKind.APP -> "Installed applications"; ActionKind.SHORTCUT -> "App shortcuts"; else -> "Settings destinations" }
                item(key = "devices/" + kind.name) { GroupLabel(heading) }
                items(list, key = { "device/" + kind.name + "/" + it.second.key }) { (_, choice) ->
                    ChoiceRow(choice.title, choice.detail, { onChoice(choice) }) {
                        if (choice.icon != null) PickerIcon(choice.icon, icons) else ActionGlyph(glyphFor(kind))
                    }
                }
            }
            if (shown.isEmpty() && installed.isEmpty()) item { QuietText("No matching actions", Modifier.padding(18.dp)) }
        }
    }
}

@Composable
private fun GroupLabel(text: String) {
    Text(text, Modifier.padding(start = 18.dp, top = 14.dp, bottom = 3.dp), color = MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
}
