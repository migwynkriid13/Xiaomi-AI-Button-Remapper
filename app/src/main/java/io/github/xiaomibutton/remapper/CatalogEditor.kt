package io.github.xiaomibutton.remapper

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.xiaomibutton.remapper.runtime.RemapperRuntime
import io.github.xiaomibutton.remapper.runtime.UiActions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
private fun AccessOptions(action: CatalogAction, report: (String) -> Unit) {
    val context = LocalContext.current
    val requirement = action.requirement
    val localNetwork = action.id == "utility.http" && Build.VERSION.SDK_INT >= 37
    if (requirement.isBlank() && !localNetwork) return
    if (requirement.isNotBlank()) QuietText("Requires " + requirement)
    fun system(intent: Intent) {
        try { context.startActivity(intent) } catch (_: Exception) { report("This setting is unavailable on your device") }
    }
    val permission = when (action.id) {
        "phone.call" -> Manifest.permission.CALL_PHONE
        "phone.answer", "phone.end" -> Manifest.permission.ANSWER_PHONE_CALLS
        "phone.sms" -> Manifest.permission.SEND_SMS
        "utility.http" -> if (localNetwork) "android.permission.ACCESS_LOCAL_NETWORK" else null
        else -> null
    }
    var denied by rememberSaveable { mutableStateOf(false) }
    val grant = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        denied = !allowed
        report(if (allowed) "Permission granted" else "Permission unavailable. You can review this app’s permissions in Settings.")
    }
    val detailUri = Uri.parse("package:" + context.packageName)
    val setting = when {
        requirement.contains("notification access", true) -> "Open notification access" to Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        requirement.contains("do not disturb", true) -> "Open Do Not Disturb access" to Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
        requirement.contains("modify system settings", true) -> "Allow modifying system settings" to Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, detailUri)
        requirement.contains("Key Mapper", true) -> context.packageManager.getLaunchIntentForPackage("io.github.sds100.keymapper")
            ?.let { "Open Key Mapper" to it }
        else -> null
    }
    if (setting != null) SecondaryButton(setting.first, { system(setting.second) })
    if (localNetwork) QuietText("Android 17 requires local network access for LAN devices. Public URLs work without that permission.")
    if (requirement == UiActions.REQUIREMENT) {
        val status by RemapperRuntime.status.collectAsState()
        QuietText("This action reads the active unlocked window when it runs.")
        if (!status.uiActionsEnabled) {
            SecondaryButton("Enable UI interactions", { RemapperRuntime.enableUiActions(context, true) })
        }
        if (!status.accessibilityConnected) {
            SecondaryButton("Open accessibility settings", { RemapperRuntime.openAccessibility(context) })
        }
    }
    if (requirement.contains("device admin", true)) {
        if (DeviceAdminLock.available(context)) QuietText("Screen-lock permission is enabled.")
        else SecondaryButton("Allow device lock", { system(DeviceAdminLock.enroll(context)) })
    }
    if (requirement.contains("Accessibility", true)) {
        SecondaryButton("Open accessibility settings", { RemapperRuntime.openAccessibility(context) })
    }
    if (permission != null && context.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
        val label = if (denied) "Open app permissions" else if (localNetwork) "Allow local network access" else "Request permission"
        SecondaryButton(label, {
            if (denied) system(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, detailUri)) else grant.launch(permission)
        })
    }
}

@Suppress("DEPRECATION")
@Composable
internal fun CatalogEditor(action: CatalogAction, current: ActionSpec, icons: PickerIconCache, choosingApp: Boolean,
    setChoosingApp: (Boolean) -> Unit, save: (ActionSpec) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val old = current.extra.takeIf { current.kind == ActionKind.CATALOG && current.value == action.id }.orEmpty()
    val appNumber = action.parameter == ActionParameter.APP_NUMBER
    var value by rememberSaveable(action.id) { mutableStateOf(if (appNumber) old.substringAfter('|', "") else old) }
    var pkg by rememberSaveable(action.id) { mutableStateOf(if (appNumber) old.substringBefore('|') else old) }
    var selectedLabel by rememberSaveable(action.id) { mutableStateOf("") }
    var pendingAudio by rememberSaveable(action.id) { mutableStateOf<String?>(null) }
    var message by rememberSaveable(action.id) { mutableStateOf<String?>(null) }

    fun commit(parameter: String, description: String = "") {
        val label = listOf(action.title, description).filter { it.isNotBlank() }.joinToString(" · ")
            .filterNot(Char::isISOControl).take(120)
        val spec = ActionSpec(ActionKind.CATALOG, action.id, parameter, label)
        try {
            validateAction(spec)
            if (action.parameter == ActionParameter.AUDIO && parameter == pendingAudio)
                context.contentResolver.takePersistableUriPermission(Uri.parse(parameter), Intent.FLAG_GRANT_READ_URI_PERMISSION)
            save(spec)
        } catch (e: Exception) { message = e.localizedMessage ?: "Invalid value" }
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            try {
                val name = withContext(Dispatchers.IO) {
                    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                        if (it.moveToFirst()) it.getString(0) else null
                    }
                }
                value = uri.toString()
                pendingAudio = value
                selectedLabel = name ?: "Audio file"
            } catch (_: Exception) { message = "Cannot keep access to that audio file. Choose a file from another provider." }
        }
    }
    val ringtone = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri = result.data?.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            if (uri != null) {
                value = uri.toString()
                scope.launch {
                    selectedLabel = withContext(Dispatchers.IO) {
                        runCatching { RingtoneManager.getRingtone(context, uri)?.getTitle(context) }.getOrNull()
                    }.orEmpty().ifBlank { "System sound" }
                }
            }
        }
    }

    if (choosingApp) {
        ChoiceList(ActionKind.APP, icons) { choice ->
            pkg = choice.action?.value.orEmpty()
            selectedLabel = choice.title
            setChoosingApp(false)
        }
        return
    }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(ScreenGutter),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        AccessOptions(action) { message = it }
        if (action.hint.isNotBlank()) QuietText(action.hint)
        if (action.parameter == ActionParameter.APP || appNumber) {
            val label = selectedLabel.ifEmpty { pkg }.ifBlank { "Choose application" }
            Panel {
                SettingRow(label, onClick = { setChoosingApp(true) }, icon = R.drawable.action_apps)
            }
        }
        when (action.parameter) {
            ActionParameter.CHOICE -> Panel {
                action.choices.forEachIndexed { index, (id, label) ->
                    if (index > 0) RowDivider()
                    SettingRow(label, onClick = { commit(id, label) })
                }
            }
            ActionParameter.TEXT, ActionParameter.POINT, ActionParameter.NUMBER, ActionParameter.APP_NUMBER -> {
                val number = appNumber || action.parameter == ActionParameter.NUMBER
                val fieldName = when {
                    appNumber -> "Seconds"
                    action.parameter == ActionParameter.POINT -> "Coordinates"
                    action.requirement == KeyMapInterop.REQUIREMENT || action.id.startsWith("maps.") -> "Mapping or group UID"
                    else -> "Value"
                }
                EditorField(value, { value = it; message = null }, Modifier.fillMaxWidth(), label = { Text(fieldName) },
                    singleLine = number || action.parameter == ActionParameter.POINT, maxLines = 5,
                    keyboardOptions = KeyboardOptions(keyboardType = if (number) KeyboardType.Number else KeyboardType.Text))
                if (action.id == "keyboard.switch") {
                    val methods by produceState<List<Pair<String,String>>>(emptyList(), context) {
                        this.value = withContext(Dispatchers.IO) {
                            val pm = context.packageManager
                            context.getSystemService(InputMethodManager::class.java).enabledInputMethodList.map { it.id to it.loadLabel(pm).toString() }
                        }
                    }
                    methods.forEach { (id, label) -> TextButton(onClick = { value = id; selectedLabel = label }) { Text(label) } }
                }
            }
            ActionParameter.AUDIO, ActionParameter.RINGTONE -> {
                if (value.isNotBlank()) QuietText(selectedLabel.ifBlank { value })
                val label = if (action.parameter == ActionParameter.AUDIO) "Choose audio file" else "Choose system sound"
                SecondaryButton(label, {
                    try {
                        if (action.parameter == ActionParameter.AUDIO) filePicker.launch(arrayOf("audio/*"))
                        else ringtone.launch(Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
                            .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALL)
                            .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                            .putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, value.takeIf { it.isNotBlank() }?.let(Uri::parse)))
                    } catch (_: Exception) { message = "No compatible Android picker is installed" }
                })
            }
            else -> Unit
        }
        message?.let { Text(it, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyMedium) }
        if (action.parameter != ActionParameter.CHOICE) PrimaryButton("Save action", {
            val result = when (action.parameter) {
                ActionParameter.NONE -> ""
                ActionParameter.APP -> pkg
                ActionParameter.APP_NUMBER -> pkg + "|" + value.trim()
                ActionParameter.TEXT -> value
                else -> value.trim()
            }
            val label = when {
                appNumber -> (selectedLabel.ifEmpty { pkg }) + " · " + value.trim() + " s"
                action.parameter == ActionParameter.APP -> selectedLabel.ifEmpty { pkg }
                selectedLabel.isNotEmpty() -> selectedLabel
                action.parameter in listOf(ActionParameter.POINT, ActionParameter.NUMBER) -> value
                else -> ""
            }
            commit(result, label)
        }, Modifier.fillMaxWidth())
    }
}
