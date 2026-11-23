package io.github.xiaomibutton.remapper

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.os.Process
import io.github.xiaomibutton.remapper.runtime.PermittedCommands

internal fun actionLabel(action: ActionSpec, context: Context, detail: Boolean = false): String = when (action.kind) {
    ActionKind.APP -> runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(action.value, 0)).toString()
    }.getOrDefault(action.value)
    ActionKind.SHORTCUT -> if (detail) {
        if (action.extra.isEmpty()) action.value else action.value + " / " + action.extra
    } else action.label.ifEmpty { action.extra.ifEmpty { "App shortcut" } }
    ActionKind.QUICK_TILE -> {
        val id = runCatching { QuickSettingsTiles.parse(action.value) }.getOrNull()
        val installed = id?.let { tile -> runCatching {
            @Suppress("DEPRECATION")
            shortcutLabel(context.packageManager.getServiceInfo(tile.component, 0)
                .loadLabel(context.packageManager).toString())
        }.getOrNull() }
        val title = action.label.ifBlank { installed ?: "Control Centre tile" }
        if (detail) "$title · ${action.value}" else title
    }
    ActionKind.CATALOG -> if (ActionLibrary.isRetired(action.value)) "Unavailable · ${ActionLibrary.find(action.value)?.title}"
        else if (detail) listOfNotNull(ActionLibrary.find(action.value)?.title ?: action.value,
        action.extra.takeIf { it.isNotBlank() }).joinToString(" · ") else action.label.ifBlank { ActionLibrary.find(action.value)?.title ?: action.value }
    ActionKind.SEQUENCE -> runCatching {
        val sequence = SequenceConfig.fromAction(action)
        if (detail) sequence.steps.mapIndexed { i, step ->
            (i + 1).toString() + ". " + actionLabel(step.action, context, true) +
                (if (step.delayMs > 0) " · wait " + step.delayMs + " ms" else "")
        }.joinToString("\n") + (if (sequence.repeatCount > 1) "\nRepeat " + sequence.repeatCount + " times" else "")
        else action.kind.title + " · " + sequence.steps.size + " steps"
    }.getOrDefault(action.kind.title)
    ActionKind.SETTINGS -> systemPages[action.value] ?: action.value
    ActionKind.URL, ActionKind.INTENT -> action.value
    ActionKind.SHELL -> "Unavailable · ${if (detail) action.value else PermittedCommands.options[action.value] ?: action.value}"
    else -> action.kind.title
}

internal data class Choice(val title: String, val detail: String, val action: ActionSpec? = null,
    val provider: ComponentName? = null, val icon: PickerIconSource? = null) {
    val key: String get() = icon?.key ?: detail
}

internal fun shortcutLabel(text: String?) = text.orEmpty().filterNot(Char::isISOControl).trim().take(120)

internal fun PackageManager.launchableActivities(packageName: String? = null) =
    queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(packageName), 0)
        .filter { it.activityInfo.exported && it.activityInfo.enabled }

@Suppress("DEPRECATION")
internal fun loadChoices(context: Context, kind: ActionKind): List<Choice> {
    val pm = context.packageManager
    fun providers(intent: Intent) = pm.queryIntentActivities(intent, 0)
        .filter { it.activityInfo.exported && it.activityInfo.enabled }
    val choices = when (kind) {
        ActionKind.APP -> pm.launchableActivities()
            .distinctBy { it.activityInfo.packageName }.map {
                val pkg = it.activityInfo.packageName
                Choice(it.loadLabel(pm).toString(), pkg, ActionSpec(kind, pkg), icon = PickerIconSource.Activity(it))
            }
        ActionKind.SHORTCUT -> buildList {
            addAll(providers(Intent(Intent.ACTION_CREATE_SHORTCUT)).map {
                Choice(it.loadLabel(pm).toString(), it.activityInfo.packageName, provider =
                    ComponentName(it.activityInfo.packageName, it.activityInfo.name), icon = PickerIconSource.Activity(it))
            })
            val launcher = context.getSystemService(LauncherApps::class.java)
            if (launcher.hasShortcutHostPermission()) {
                val flags = LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED or
                    LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST
                val query = LauncherApps.ShortcutQuery().setQueryFlags(flags)
                val shortcuts = try { launcher.getShortcuts(query, Process.myUserHandle()).orEmpty() }
                    catch (_: SecurityException) { emptyList() } catch (_: IllegalStateException) { emptyList() }
                shortcuts.filter { it.isEnabled }.forEach {
                    val label = shortcutLabel(it.shortLabel?.toString())
                    add(Choice(label.ifEmpty { it.id }, it.`package`, ActionSpec(kind, it.`package`, it.id, label),
                        icon = PickerIconSource.Shortcut(it)))
                }
            }
        }
        ActionKind.QUICK_TILE -> pm.queryIntentServices(Intent(QuickSettingsTiles.ACTION), 0)
            .filter {
                val service = it.serviceInfo
                service != null && service.exported && service.enabled &&
                    service.permission == QuickSettingsTiles.BIND_PERMISSION
            }
            .distinctBy { ComponentName(it.serviceInfo.packageName, it.serviceInfo.name) }
            .map {
                val service = it.serviceInfo
                val id = QuickTileId(service.packageName, service.name)
                val app = shortcutLabel(service.applicationInfo.loadLabel(pm).toString())
                val title = shortcutLabel(it.loadLabel(pm).toString()).ifBlank { service.name.substringAfterLast('.') }
                Choice(title, "$app · Add to Control Centre first if it is not already there",
                    ActionSpec(kind, id.encoded, label = title), icon = PickerIconSource.Service(it))
            }
        ActionKind.SETTINGS -> systemPages.filterKeys { Intent(it).resolveActivity(pm) != null }
            .map { (id, label) -> Choice(label, id, ActionSpec(kind, id)) }
        else -> emptyList()
    }
    return choices.sortedBy { it.title.lowercase() }
}
