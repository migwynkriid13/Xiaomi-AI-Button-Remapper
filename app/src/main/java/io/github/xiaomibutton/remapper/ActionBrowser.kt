package io.github.xiaomibutton.remapper

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
private fun SearchField(value: String, onChange: (String) -> Unit, label: String = "Search") {
    val colors = MaterialTheme.colorScheme
    OutlinedTextField(
        value, onChange,
        placeholder = { Text(label, Modifier.clearAndSetSemantics {}) }, singleLine = true, shape = CircleShape,
        modifier = Modifier.fillMaxWidth().padding(horizontal = ScreenGutter, vertical = 4.dp)
            .semantics { contentDescription = label },
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = colors.surfaceContainer, unfocusedContainerColor = colors.surfaceContainer,
            unfocusedBorderColor = colors.outlineVariant, unfocusedPlaceholderColor = colors.onSurfaceVariant
        ),
        leadingIcon = { Icon(painterResource(R.drawable.action_search), contentDescription = null, Modifier.size(20.dp)) }
    )
}

@Composable
internal fun ChoiceRow(title: String, detail: String, onClick: () -> Unit, leading: @Composable () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(onClick, Modifier.fillMaxWidth().semantics { role = Role.Button }, shape = TileShape, color = colors.surfaceContainer,
        border = BorderStroke(1.dp, colors.outlineVariant)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(horizontal = 11.dp, vertical = 9.dp),
            horizontalArrangement = Arrangement.spacedBy(11.dp), verticalAlignment = Alignment.CenterVertically
        ) {
            leading()
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                if (detail.isNotBlank()) QuietText(detail)
            }
            LineIcon(Mark.NEXT, Modifier.size(16.dp), colors.onSurfaceVariant)
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
    val searchLabel = when (kind) {
        ActionKind.APP -> "Search installed apps"
        ActionKind.SHORTCUT -> "Search shortcuts"
        ActionKind.QUICK_TILE -> "Search Control Centre tiles"
        ActionKind.SETTINGS -> "Search system settings"
        else -> "Search"
    }
    SearchField(query, { query = it }, searchLabel)
    val available = choices
    if (available == null) LinearProgressIndicator(Modifier.fillMaxWidth())
    else {
        val filtered = available.filter { it.title.contains(query, true) || it.detail.contains(query, true) }
        if (filtered.isEmpty()) QuietText(failure ?: when {
            query.isNotEmpty() -> "No matching entries"
            kind == ActionKind.SHORTCUT -> "No shortcut creators are installed. Android also exposes launcher shortcuts to authorized shortcut hosts."
            kind == ActionKind.SETTINGS -> "This device publishes no supported Settings destinations."
            else -> "No entries available"
        }, Modifier.padding(ScreenGutter))
        LazyColumn(contentPadding = PaddingValues(start = ScreenGutter, end = ScreenGutter, top = 8.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
            "Control Centre" to listOf(ActionKind.QUICK_TILE),
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
    SearchField(query, { query = it }, "Search actions and apps")
    val colors = MaterialTheme.colorScheme
    LazyRow(contentPadding = PaddingValues(horizontal = ScreenGutter), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        items(groups, key = { it }) { group ->
            FilterChip(
                selected = group == filter, onClick = { filter = group }, label = { Text(group) }, shape = CircleShape,
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = colors.surfaceContainer, labelColor = colors.onSurfaceVariant,
                    selectedContainerColor = colors.primaryContainer, selectedLabelColor = colors.onPrimaryContainer
                ),
                border = FilterChipDefaults.filterChipBorder(enabled = true, selected = group == filter,
                    borderColor = colors.outlineVariant, selectedBorderColor = colors.primaryContainer)
            )
        }
    }
    val search = query.trim()
    val shown = remember(entries, search, filter) { entries.filter {
        (filter == "All" || filter == it.group) && (search.isEmpty() || it.title.contains(search, true) ||
            it.group.contains(search, true) || it.catalog?.id?.contains(search, true) == true)
    }.groupBy { it.group } }
    val includeInstalled = search.isNotEmpty() && filter in setOf("All", "Apps", "Settings", "Control Centre")
    val installed = if (includeInstalled) {
        val choices by produceState<List<Pair<ActionKind, Choice>>>(emptyList(), context) {
            value = withContext(Dispatchers.IO) {
                listOf(ActionKind.APP, ActionKind.SHORTCUT, ActionKind.SETTINGS, ActionKind.QUICK_TILE).flatMap { kind ->
                    try { loadChoices(context, kind).map { kind to it } } catch (_: Exception) { emptyList() }
                }
            }
        }
        choices.filter { (kind, choice) ->
            (filter != "Settings" || kind == ActionKind.SETTINGS) &&
            (filter != "Control Centre" || kind == ActionKind.QUICK_TILE) &&
            (choice.title.contains(search, true) || choice.detail.contains(search, true)) }
    } else emptyList()
    key(filter, search) {
        LazyColumn(contentPadding = PaddingValues(start = ScreenGutter, end = ScreenGutter, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            shown.forEach { (group, list) ->
                item(key = "group/" + group) { GroupLabel(group) }
                items(list, key = { "action/" + it.id }) { item ->
                    ChoiceRow(item.title, item.detail, {
                        if (item.catalog != null) onCatalog(item.catalog) else onBasic(item.kind!!)
                    }) { ActionGlyph(glyphFor(item.kind, item.catalog)) }
                }
            }
            installed.groupBy { it.first }.forEach { (kind, list) ->
                val heading = when (kind) {
                    ActionKind.APP -> "Installed applications"
                    ActionKind.SHORTCUT -> "App shortcuts"
                    ActionKind.QUICK_TILE -> "Installed Control Centre tiles"
                    else -> "Settings destinations"
                }
                item(key = "devices/" + kind.name) { GroupLabel(heading) }
                items(list, key = { "device/" + kind.name + "/" + it.second.key }) { (_, choice) ->
                    ChoiceRow(choice.title, choice.detail, { onChoice(choice) }) {
                        if (choice.icon != null) PickerIcon(choice.icon, icons) else ActionGlyph(glyphFor(kind))
                    }
                }
            }
            if (shown.isEmpty() && installed.isEmpty()) item { QuietText("No matching actions", Modifier.padding(vertical = 12.dp)) }
        }
    }
}

@Composable
private fun GroupLabel(text: String) {
    GroupTitle(text, Modifier.padding(bottom = 2.dp))
}
