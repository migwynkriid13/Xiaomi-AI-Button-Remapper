package io.github.xiaomibutton.remapper

import android.app.Activity
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun ActionPicker(slot: GestureSlot, current: ActionSpec, dismiss: () -> Unit, allowSequence: Boolean = true, save: (ActionSpec) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val configuration = LocalConfiguration.current
    val icons = remember(context, configuration.densityDpi, configuration.uiMode) { PickerIconCache() }
    val browserState = rememberSaveableStateHolder()
    var categoryName by rememberSaveable(slot.name) { mutableStateOf<String?>(null) }
    var catalogId by rememberSaveable(slot.name) { mutableStateOf<String?>(null) }
    var choosingCatalogApp by rememberSaveable(catalogId) { mutableStateOf(false) }
    var provider by rememberSaveable { mutableStateOf<String?>(null) }
    var problem by rememberSaveable { mutableStateOf<String?>(null) }
    val category = categoryName?.let(ActionKind::valueOf)
    val catalog = catalogId?.let(ActionLibrary::find)
    val atHome = category == null && catalog == null
    val shortcutCreator = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            scope.launch {
                try {
                    @Suppress("DEPRECATION")
                    val intent = data.getParcelableExtra<Intent>(Intent.EXTRA_SHORTCUT_INTENT) ?: Intent(data).also {
                        it.removeExtra(Intent.EXTRA_SHORTCUT_NAME)
                        it.removeExtra(Intent.EXTRA_SHORTCUT_ICON)
                        it.removeExtra(Intent.EXTRA_SHORTCUT_ICON_RESOURCE)
                        require(it.component != null || it.data != null ||
                            (it.action != null && it.action != Intent.ACTION_CREATE_SHORTCUT)) {
                            "The provider returned no launchable shortcut intent"
                        }
                    }
                    val encoded = intent.toUri(Intent.URI_INTENT_SCHEME)
                    val stored = Intent.parseUri(encoded, Intent.URI_INTENT_SCHEME)
                    require(intent.clipData == null && intent.extras?.keySet().orEmpty() == stored.extras?.keySet().orEmpty()) {
                        "This provider returned shortcut data that cannot be saved as an Android intent URI"
                    }
                    val label = shortcutLabel(data.getStringExtra(Intent.EXTRA_SHORTCUT_NAME))
                    val action = ActionSpec(ActionKind.SHORTCUT, encoded, label = label)
                    validateAction(action)
                    withContext(Dispatchers.IO) { runCatching { rememberShortcutArtwork(context, data, encoded, provider) } }
                    save(action)
                } catch (e: Exception) { problem = e.localizedMessage ?: "The shortcut could not be read" }
            }
        }
    }
    fun pick(choice: Choice) {
        try {
            if (choice.action != null) { validateAction(choice.action); save(choice.action) }
            else {
                provider = choice.provider?.flattenToString()
                shortcutCreator.launch(Intent(Intent.ACTION_CREATE_SHORTCUT).setComponent(choice.provider))
            }
        } catch (e: Exception) { problem = e.localizedMessage ?: "This entry is unavailable" }
    }
    fun basic(kind: ActionKind) {
        when (kind) {
            ActionKind.NONE, ActionKind.CAMERA, ActionKind.FLASHLIGHT -> save(ActionSpec(kind))
            else -> categoryName = kind.name
        }
    }
    fun openCatalog(item: CatalogAction) {
        if (item.parameter == ActionParameter.NONE && item.requirement.isBlank() && item.hint.isBlank()) {
            val action = ActionSpec(ActionKind.CATALOG, item.id, label = item.title)
            try { validateAction(action); save(action) } catch (e: Exception) { problem = e.message }
        } else catalogId = item.id
    }
    fun goBack() {
        if (choosingCatalogApp) choosingCatalogApp = false
        else if (atHome) dismiss() else { categoryName = null; catalogId = null; problem = null }
    }

    Dialog(onDismissRequest = ::goBack, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.widthIn(max = 660.dp).fillMaxWidth(.96f).imePadding()
            .heightIn(max = 850.dp).fillMaxHeight(.93f), shape = MaterialTheme.shapes.extraLarge) {
            Column(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxWidth().padding(start = 18.dp, end = 12.dp, top = 5.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(slot.title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        TextButton(onClick = ::goBack) { Text(if (atHome) "Close" else "Back") }
                    }
                    Text(if (choosingCatalogApp) "Choose application" else catalog?.title ?: category?.title ?: "Choose action",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold)
                }
                problem?.let { Text(it, Modifier.padding(horizontal = 18.dp, vertical = 6.dp),
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                when {
                    category == ActionKind.SEQUENCE && allowSequence -> SequenceEditor(slot, current, save)
                    catalog != null -> key(catalog.id) {
                        CatalogEditor(catalog, current, icons, choosingCatalogApp, { choosingCatalogApp = it }, save)
                    }
                    category == null -> browserState.SaveableStateProvider("actions") {
                        ActionBrowser(slot, icons, allowSequence, ::basic, ::openCatalog, ::pick)
                    }
                    category in setOf(ActionKind.APP, ActionKind.SHORTCUT, ActionKind.SETTINGS) ->
                        ChoiceList(category, icons, ::pick)
                    else -> key(category) {
                        var value by rememberSaveable { mutableStateOf(if (category == current.kind) current.value else "") }
                        Column(Modifier.verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            if (category == ActionKind.DND || category == ActionKind.SILENT) {
                                QuietText("Android requires Do Not Disturb access for interruption policy and some silent-mode changes.")
                                OutlinedButton(onClick = { try { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)) }
                                    catch (_: Exception) { problem = "This setting is unavailable" } }) { Text("Open Do Not Disturb access") }
                            } else {
                                val website = category == ActionKind.URL
                                OutlinedTextField(value, { value = it; problem = null }, modifier = Modifier.fillMaxWidth(),
                                    label = { Text(if (website) "Website address" else "Intent URI") }, maxLines = 5)
                                QuietText(if (website) "https://example.org" else "intent:#Intent;action=android.settings.DISPLAY_SETTINGS;end")
                            }
                            Button(onClick = {
                                val action = ActionSpec(category, value.trim())
                                try { validateAction(action); save(action) } catch (e: Exception) { problem = e.localizedMessage ?: "Invalid action" }
                            }) { Text("Save action") }
                        }
                    }
                }
            }
        }
    }
}

