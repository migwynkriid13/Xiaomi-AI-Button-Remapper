package io.github.xiaomibutton.remapper

import android.content.Context
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.xiaomibutton.remapper.runtime.RemapperRuntime
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        RemapperRuntime.initialize(this)
        setContent { RemapperTheme { RemapperApp() } }
    }

    override fun onResume() {
        super.onResume()
        RemapperRuntime.uiResumed(this)
    }

    override fun onPause() {
        RemapperRuntime.uiPaused()
        super.onPause()
    }
}

private enum class Page(val title: String, val icon: Mark) {
    MAPPINGS("Button", Mark.BUTTON), DIAGNOSTICS("Diagnostics", Mark.PULSE), SETTINGS("Settings", Mark.TUNE)
}

private suspend fun readBackup(context: Context, uri: Uri): AppConfig = withContext(Dispatchers.IO) {
    val input = context.contentResolver.openInputStream(uri) ?: error("Cannot open this file")
    val bytes = ByteArrayOutputStream()
    input.use { stream ->
        val buffer = ByteArray(4096)
        var length = stream.read(buffer)
        while (length >= 0) {
            require(bytes.size() + length <= ConfigJson.MAX_BYTES) { "Configuration exceeds 64 KiB" }
            bytes.write(buffer, 0, length)
            length = stream.read(buffer)
        }
    }
    ConfigJson.decode(bytes.toString("UTF-8"), imported = true)
}

@Composable
private fun RemapperApp() {
    val context = LocalContext.current
    val store = remember { ConfigStore(context) }
    val config: AppConfig? by store.config.collectAsState(initial = null)
    val status by RemapperRuntime.status.collectAsState()
    val scope = rememberCoroutineScope()
    val messages = remember { SnackbarHostState() }
    var page by rememberSaveable { mutableStateOf(Page.MAPPINGS) }
    LaunchedEffect(config?.showDiagnostics) {
        if (config?.showDiagnostics == false && page == Page.DIAGNOSTICS) page = Page.MAPPINGS
    }
    fun navigate(next: Page) {
        if (page == Page.DIAGNOSTICS && next != Page.DIAGNOSTICS) RemapperRuntime.endTest()
        page = next
    }
    var slot by rememberSaveable { mutableStateOf<GestureSlot?>(null) }
    var pendingImport by remember { mutableStateOf<AppConfig?>(null) }
    fun message(text: String) { scope.launch { messages.showSnackbar(text) } }
    fun change(transform: (AppConfig) -> AppConfig) {
        scope.launch { runCatching { store.update(transform) }.onFailure { message("Could not save: " + it.localizedMessage) } }
    }
    fun launchSystem(intent: Intent) {
        runCatching { context.startActivity(intent) }.onFailure { message("This screen is unavailable on your device") }
    }
    fun openDocumentPicker(launch: () -> Unit) {
        try {
            launch()
        } catch (_: android.content.ActivityNotFoundException) {
            message("The Android document picker is unavailable on this device")
        }
    }
    fun startRemapping() {
        scope.launch {
            runCatching {
                store.update { it.copy(enabled = true) }
                RemapperRuntime.start(context)
            }.onFailure {
                RemapperRuntime.stop(context)
                message("Could not start: " + it.localizedMessage)
            }
        }
    }
    val notificationRequest = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        if (allowed) startRemapping() else message("Allow notifications to keep the Stop action available during remapping.")
    }
    fun enable() {
        if (!status.shizukuAuthorized) return
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) notificationRequest.launch(Manifest.permission.POST_NOTIFICATIONS)
        else startRemapping()
    }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val snapshot = config
        if (uri != null && snapshot != null) scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val stream = context.contentResolver.openOutputStream(uri, "wt") ?: error("Cannot open destination")
                    stream.bufferedWriter(Charsets.UTF_8).use { it.write(ConfigJson.encode(snapshot, export = true)) }
                }
            }.onSuccess { message("Configuration exported") }.onFailure { message("Export failed: " + it.localizedMessage) }
        }
    }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            runCatching { readBackup(context, uri) }.onSuccess { pendingImport = it }
                .onFailure { message("Import failed: " + it.localizedMessage) }
        }
    }
    BackHandler(enabled = page != Page.MAPPINGS && slot == null && pendingImport == null) { navigate(Page.MAPPINGS) }
    Scaffold(containerColor = MaterialTheme.colorScheme.background, snackbarHost = { SnackbarHost(messages) },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                Page.entries.filter { it != Page.DIAGNOSTICS || config?.showDiagnostics == true }.forEach { item ->
                    NavigationBarItem(selected = page == item, onClick = { navigate(item) },
                        icon = { LineIcon(item.icon) }, label = { Text(item.title) })
                }
            }
        }) { insets ->
        val current = config
        if (current == null) Box(Modifier.fillMaxSize().padding(insets), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        } else key(page) {
            Column(Modifier.fillMaxSize().padding(insets).verticalScroll(rememberScrollState())
                .padding(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (page) {
                    Page.MAPPINGS -> Dashboard(current, status, { slot = it }, { on ->
                        if (on) enable() else RemapperRuntime.stop(context)
                    }, ::enable, ::launchSystem)
                    Page.DIAGNOSTICS -> Diagnostics(status, ::launchSystem)
                    Page.SETTINGS -> Preferences(current, status, ::change, ::launchSystem,
                        { openDocumentPicker { export.launch("xiaomi-button-config.json") } },
                        { openDocumentPicker { import.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) } },
                        { RemapperRuntime.stop(context); message("Xiaomi default behavior restored") })
                }
            }
        }
    }
    val editing = slot
    if (editing != null && config != null) key(editing) {
        ActionPicker(editing, config!!.action(editing), { slot = null }) { action ->
            change { it.withAction(editing, action) }
            slot = null
        }
    }
    val candidate = pendingImport
    if (candidate != null) AlertDialog(
        onDismissRequest = { pendingImport = null }, title = { Text("Import configuration?") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Text("This replaces your gestures and timing. Review the actions before enabling remapping again.")
                GestureSlot.entries.forEach { s ->
                    val action = candidate.action(s)
                    Text(s.title, style = MaterialTheme.typography.labelLarge)
                    QuietText(actionLabel(action, context, detail = true))
                }
            }
        }, confirmButton = { TextButton(onClick = {
            pendingImport = null
            scope.launch {
                RemapperRuntime.stop(context)
                runCatching { store.update { candidate.copy(enabled = false, showDiagnostics = it.showDiagnostics) } }
                    .onSuccess { message("Configuration imported") }
                    .onFailure { message("Import failed: " + it.localizedMessage) }
            }
        }) { Text("Import") } }, dismissButton = { TextButton(onClick = { pendingImport = null }) { Text("Cancel") } }
    )
}

