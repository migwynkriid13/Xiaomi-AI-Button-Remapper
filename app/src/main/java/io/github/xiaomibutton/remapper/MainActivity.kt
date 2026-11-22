package io.github.xiaomibutton.remapper

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.xiaomibutton.remapper.backscreen.RearScreenActivity
import io.github.xiaomibutton.remapper.backscreen.resumeRearScreen
import io.github.xiaomibutton.remapper.runtime.RemapperRuntime
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.isNavigationBarContrastEnforced = false
        RemapperRuntime.initialize(this)
        setContent { RemapperTheme { RemapperApp() } }
    }

    override fun onResume() {
        super.onResume()
        RemapperRuntime.uiResumed(this)
        resumeRearScreen(this)
    }

    override fun onPause() {
        RemapperRuntime.uiPaused()
        super.onPause()
    }
}

private enum class Page(val title: String, val icon: Mark) {
    MAPPINGS("Button", Mark.BUTTON), REAR_SCREEN("Back screen", Mark.REAR_SCREEN),
    DIAGNOSTICS("Diagnostics", Mark.PULSE), SETTINGS("Settings", Mark.TUNE)
}

@Composable
private fun PageNavigation(page: Page, pages: List<Page>, navigate: (Page) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(color = colors.surfaceContainer) {
        Column {
            HorizontalDivider(color = colors.outlineVariant)
            Box(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing.only(
                WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom
            )), contentAlignment = Alignment.Center) {
                Row(
                    Modifier.widthIn(max = ScreenWidth).fillMaxWidth().padding(horizontal = 12.dp)
                        .selectableGroup(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    pages.forEach { item ->
                        val selected = page == item
                        val tint = if (selected) colors.onPrimaryContainer else colors.onSurfaceVariant
                        Column(
                            Modifier.weight(1f).heightIn(min = 64.dp).clip(PanelShape)
                                .selectable(selected, role = Role.Tab) { navigate(item) }
                                .padding(horizontal = 4.dp, vertical = 7.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically)
                        ) {
                            Box(
                                Modifier.size(48.dp, 28.dp).clip(CircleShape).then(
                                    if (selected) Modifier.background(colors.primaryContainer) else Modifier
                                ), contentAlignment = Alignment.Center
                            ) {
                                LineIcon(item.icon, Modifier.size(20.dp), tint)
                            }
                            Text(item.title, style = MaterialTheme.typography.labelMedium, color = tint)
                        }
                    }
                }
            }
        }
    }
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
    val pageState = rememberSaveableStateHolder()
    val installedVersion = remember(context) { context.packageManager.getPackageInfo(context.packageName, 0).versionName }
    var updateChecked by rememberSaveable { mutableStateOf(false) }
    var updateVersion by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(installedVersion) {
        if (!updateChecked && installedVersion != null) {
            updateVersion = checkForGitHubUpdate(installedVersion)
            updateChecked = true
        }
    }
    val rearOnly = remember { xiaomi17ModelLabel(Build.MODEL, Build.DEVICE) != null }
    val home = if (rearOnly) Page.REAR_SCREEN else Page.MAPPINGS
    val pages = Page.entries.filter { item ->
        when (item) {
            Page.MAPPINGS -> !rearOnly
            Page.DIAGNOSTICS -> !rearOnly && config?.showDiagnostics == true
            else -> true
        }
    }
    var page by rememberSaveable { mutableStateOf(home) }
    LaunchedEffect(pages) {
        if (config != null && page !in pages) page = home
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
    val showUpdate: @Composable () -> Unit = {
        val available = updateVersion
        if (available != null && installedVersion != null) {
            UpdateNotice(
                installedVersion, available,
                download = { launchSystem(Intent(Intent.ACTION_VIEW, Uri.parse(releaseApkUrl(available)))) },
                dismiss = { updateVersion = null }
            )
        }
    }
    fun startRemapping() {
        if (!status.shizukuAuthorized) return
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
    BackHandler(enabled = page != home && slot == null && pendingImport == null) { navigate(home) }
    Scaffold(containerColor = MaterialTheme.colorScheme.background, snackbarHost = {
        SnackbarHost(messages) { data -> Snackbar(data, shape = TileShape) }
    }, bottomBar = { PageNavigation(page, pages, ::navigate) }) { insets ->
        val current = config
        if (current == null) Box(Modifier.fillMaxSize().padding(insets), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        } else pageState.SaveableStateProvider(page.name) {
            Box(
                Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets),
                contentAlignment = Alignment.TopCenter
            ) {
                Column(
                    Modifier.widthIn(max = ScreenWidth).fillMaxSize().verticalScroll(rememberScrollState())
                        .padding(start = ScreenGutter, end = ScreenGutter, top = 15.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    when (page) {
                        Page.MAPPINGS -> Dashboard(current, status, { slot = it }, { on ->
                            if (on) startRemapping() else RemapperRuntime.stop(context)
                        }, ::startRemapping, ::launchSystem, showUpdate)
                        Page.REAR_SCREEN -> {
                            if (rearOnly) showUpdate()
                            RearScreenOverview { feature -> launchSystem(RearScreenActivity.intent(context, feature)) }
                        }
                        Page.DIAGNOSTICS -> Diagnostics(status, ::launchSystem)
                        Page.SETTINGS -> if (rearOnly) RearOnlyPreferences(::launchSystem)
                        else Preferences(current, status, ::change, ::launchSystem,
                            { openDocumentPicker { export.launch("xiaomi-button-config.json") } },
                            { openDocumentPicker { import.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) } },
                            { RemapperRuntime.stop(context); message("Xiaomi default behavior restored") })
                    }
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
    if (candidate != null) RemapperDialog(
        onDismissRequest = { pendingImport = null }, title = { Text("Import configuration?") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Text("This replaces your gestures and timing. Review the actions before enabling remapping again.")
                GestureSlot.entries.forEach { s ->
                    val action = candidate.action(s)
                    Panel { SettingRow(s.title, actionLabel(action, context, detail = true)) }
                }
            }
        }, confirmButton = { PrimaryButton("Import", onClick = {
            pendingImport = null
            scope.launch {
                RemapperRuntime.stop(context)
                runCatching { store.update { candidate.copy(enabled = false, showDiagnostics = it.showDiagnostics) } }
                    .onSuccess { message("Configuration imported") }
                    .onFailure { message("Import failed: " + it.localizedMessage) }
            }
        }) }, dismissButton = { TextButton(onClick = { pendingImport = null }) { Text("Cancel") } }
    )
}

