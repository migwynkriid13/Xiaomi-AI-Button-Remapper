package io.github.xiaomibutton.remapper

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

@Composable
internal fun DiagnosticsLogSection() {
    val log by AppLog.entries.collectAsState()
    val context = LocalContext.current
    var viewing by rememberSaveable { mutableStateOf(false) }
    val latest = log.asReversed()
    GroupTitle("Log")
    Panel {
        if (latest.isEmpty()) QuietText("App activity will appear here.", Modifier.padding(16.dp))
        else {
            SelectionContainer {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    latest.take(4).forEach { DiagnosticLine(it) }
                }
            }
            SecondaryButton("View log", { viewing = true }, Modifier.padding(start = 16.dp, bottom = 14.dp))
        }
    }
    if (viewing) EditorPage("Log", "Diagnostics", atRoot = true, back = { viewing = false }) {
        PrimaryButton("Copy log", {
            context.getSystemService(ClipboardManager::class.java)
                .setPrimaryClip(ClipData.newPlainText("Xiaomi Remapper diagnostics", log.joinToString("\n")))
            Toast.makeText(context, "Log copied", Toast.LENGTH_SHORT).show()
        }, Modifier.padding(horizontal = ScreenGutter, vertical = 8.dp), enabled = log.isNotEmpty())
        SelectionContainer {
            LazyColumn(
                Modifier.fillMaxSize().padding(horizontal = ScreenGutter),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(latest) { line ->
                    Panel { DiagnosticLine(line, Modifier.padding(12.dp)) }
                }
            }
        }
    }
}

@Composable
private fun DiagnosticLine(line: String, modifier: Modifier = Modifier) {
    Text(
        line,
        modifier.fillMaxWidth(),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface,
        fontFamily = FontFamily.Monospace
    )
}
