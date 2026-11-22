package io.github.xiaomibutton.remapper

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import java.net.URI
import java.util.UUID

internal enum class ActionParameter { NONE, APP, CHOICE, TEXT, AUDIO, NUMBER, POINT, APP_NUMBER, RINGTONE }

internal data class CatalogAction(
    val id: String,
    val title: String,
    val group: String,
    val parameter: ActionParameter = ActionParameter.NONE,
    val requirement: String = "",
    val choices: List<Pair<String, String>> = emptyList(),
    val hint: String = "",
)

internal object ActionLibrary {
    val actions by lazy { DeviceActions.actions + MediaActions.actions + InteractionActions.actions }
    private val retired by lazy { DeviceActions.retired.associateBy { it.id } }
    private val byId by lazy { actions.associateBy { it.id }.also { check(it.size == actions.size) { "Duplicate action ID" } } }
    fun find(id: String): CatalogAction? = byId[id] ?: retired[id]
    fun isRetired(id: String): Boolean = id in retired

    fun validate(id: String, value: String) {
        val action = requireNotNull(find(id)) { "Unknown action: $id" }
        when (action.parameter) {
            ActionParameter.NONE -> require(value.isEmpty()) { "This action takes no parameter" }
            ActionParameter.APP -> validatePackage(value)
            ActionParameter.CHOICE -> require(action.choices.any { it.first == value }) { "Choose an available option" }
            ActionParameter.TEXT, ActionParameter.POINT -> require(value.isNotBlank()) { action.hint.ifBlank { "Enter a value" } }
            ActionParameter.NUMBER -> require(value.toIntOrNull() != null) { "Enter a whole number" }
            ActionParameter.APP_NUMBER -> {
                validatePackage(value.substringBefore('|'))
                require(value.substringAfter('|', "").toIntOrNull() in 1..3600) { "Enter a seek interval between 1 and 3600 seconds" }
            }
            ActionParameter.AUDIO, ActionParameter.RINGTONE -> {
                val uri = URI(value)
                require(uri.scheme == "content" && !uri.authority.isNullOrBlank()) { "Choose a sound from Android's picker" }
            }
        }
        if (action.requirement == KeyMapInterop.REQUIREMENT || id == "maps.trigger") KeyMapInterop.uid(value)
        DeviceActions.validate(id, value)
        MediaActions.validate(id, value)
        InteractionActions.validate(id, value)
    }
}

internal object KeyMapInterop {
    const val REQUIREMENT = "Key Mapper mapping"
    private const val PACKAGE = "io.github.sds100.keymapper"
    private const val RECEIVER = "io.github.sds100.keymapper.api.TriggerKeyMapsBroadcastReceiver"
    fun action(id: String, title: String, group: String) = CatalogAction(id, title, group,
        ActionParameter.TEXT, REQUIREMENT, hint = "In Key Mapper configure this action, enable triggering from other apps, then paste the mapping UID here.")
    fun uid(value: String): UUID = UUID.fromString(value).also {
        require(it.toString().equals(value, ignoreCase = true)) { "Enter the complete Key Mapper mapping UID" }
    }
    fun trigger(context: Context, uuid: String): String {
        val id = uid(uuid).toString()
        val component = ComponentName(PACKAGE, RECEIVER)
        check(runCatching { context.packageManager.getReceiverInfo(component, 0).exported }.getOrDefault(false)) {
            "Install Key Mapper and enable external triggering for this mapping"
        }
        context.sendBroadcast(Intent("$PACKAGE.ACTION_TRIGGER_KEYMAP_BY_UID").setComponent(component)
            .putExtra("$PACKAGE.EXTRA_KEYMAP_UID", id))
        return "Sent the trigger to Key Mapper"
    }

    fun control(context: Context, id: String, value: String): String {
        val parts = id.split('.')
        val verb = parts.last().uppercase()
        val type = parts.getOrNull(1)
        val group = type == "group"
        val single = type == "map"
        val target = when {
            group -> "EnableGroupBroadcastReceiver"
            single -> "EnableKeyMapsBroadcastReceiver"
            else -> "PauseMappingsBroadcastReceiver"
        }
        val operation = when {
            group -> verb + "_GROUP"
            single -> verb + "_KEY_MAP"
            else -> verb + "_MAPPINGS"
        }
        val component = ComponentName(PACKAGE, "$PACKAGE.api.$target")
        check(runCatching { context.packageManager.getReceiverInfo(component, 0).exported }.getOrDefault(false)) {
            "Install a compatible Key Mapper version for this control"
        }
        val intent = Intent("$PACKAGE.ACTION_$operation").setComponent(component)
        if (group || single) intent.putExtra(if (group) "$PACKAGE.EXTRA_GROUP_UID" else "$PACKAGE.EXTRA_KEYMAP_UID", uid(value).toString())
        context.sendBroadcast(intent)
        return "Sent the mapping control to Key Mapper"
    }
}
