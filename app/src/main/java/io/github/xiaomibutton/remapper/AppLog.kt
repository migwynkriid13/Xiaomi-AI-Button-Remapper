package io.github.xiaomibutton.remapper

import android.app.Application
import android.content.Context
import android.os.Build
import android.util.AtomicFile
import android.util.Log
import java.io.FileNotFoundException
import java.io.IOException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class RemapperApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLog.initialize(this)
        val version = packageManager.getPackageInfo(packageName, 0).versionName
        AppLog.add(LogArea.APP, "App started · ${version.orEmpty()} · ${Build.MODEL} · Android ${Build.VERSION.RELEASE}")
    }
}

internal enum class LogArea(val title: String) {
    APP("App"), SHIZUKU("Shizuku"), BUTTON("AI button"), REAR("Back screen")
}

/** A device-local history shared by the foreground services and Diagnostics. */
internal object AppLog {
    private const val TAG = "RemapperLog"
    private const val MAX_ENTRIES = 400
    private const val MAX_MESSAGE = 420
    private val lock = Any()
    private val timestamp = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss.SSS", Locale.ROOT)
    private val links = Regex("""(?:https?|content|file)://\S+""", RegexOption.IGNORE_CASE)
    private val changes = Channel<Unit>(Channel.CONFLATED)
    private val worker = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutable = MutableStateFlow<List<String>>(emptyList())
    val entries = mutable.asStateFlow()
    private var initialized = false

    @Synchronized
    fun initialize(context: Context) {
        if (initialized) return
        initialized = true
        val file = AtomicFile(context.noBackupFilesDir.resolve("diagnostics.log"))
        worker.launch {
            val previous = try {
                file.openRead().bufferedReader().use { it.readLines().takeLast(MAX_ENTRIES) }
            } catch (_: FileNotFoundException) {
                emptyList()
            } catch (error: IOException) {
                Log.w(TAG, "Could not load the diagnostics log", error)
                emptyList()
            }
            synchronized(lock) { mutable.value = (previous + mutable.value).takeLast(MAX_ENTRIES) }
            for (ignored in changes) {
                delay(180)
                save(file, mutable.value.joinToString("\n"))
            }
        }
    }

    fun add(area: LogArea, message: String) {
        val summary = links.replace(message.replace('\n', ' ').replace('\r', ' '), "[link]").take(MAX_MESSAGE)
        val line = "${timestamp.format(LocalDateTime.now())} · ${area.title} · $summary"
        synchronized(lock) { mutable.value = (mutable.value + line).takeLast(MAX_ENTRIES) }
        Log.i(TAG, "${area.title}: $summary")
        changes.trySend(Unit)
    }

    private fun save(file: AtomicFile, text: String) {
        try {
            val stream = file.startWrite()
            try {
                stream.write(text.toByteArray(Charsets.UTF_8))
                file.finishWrite(stream)
            } catch (error: IOException) {
                file.failWrite(stream)
                throw error
            }
        } catch (error: IOException) {
            Log.w(TAG, "Could not save the diagnostics log", error)
        }
    }
}
