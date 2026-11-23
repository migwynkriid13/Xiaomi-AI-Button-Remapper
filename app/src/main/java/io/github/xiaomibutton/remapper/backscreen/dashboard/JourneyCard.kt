package io.github.xiaomibutton.remapper.backscreen.dashboard

import android.app.Notification
import android.content.Context
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.service.notification.StatusBarNotification
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.*
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import org.json.JSONObject

internal fun journey(n: StatusBarNotification): Boolean =
    n.notification.category == Notification.CATEGORY_NAVIGATION ||
        n.notification.extras.containsKey("miui.focus.param") ||
        (n.packageName == "com.ubercab" &&
            n.notification.channelId?.startsWith("trip_take_ride") == true) ||
        (n.packageName in
            setOf("ee.mtakso.client", "com.autonavi.minimap", "com.google.android.apps.maps") &&
            n.isOngoing)

internal fun liveUpdate(n: Notification) =
    Build.VERSION.SDK_INT >= 36 && n.flags and Notification.FLAG_PROMOTED_ONGOING != 0

@Composable
internal fun JourneyDetails(s: DashboardStore, n: StatusBarNotification) {
    val note = n.notification
    val extras = note.extras
    val details =
        remember(n.key, n.postTime, note) {
            runCatching {
                    val focus = extras.getString("miui.focus.param")?.take(32768)?.let(::JSONObject)
                    buildList {
                        focus?.optString("title")?.takeIf { it.isNotBlank() }?.let(::add)
                        focus?.optString("content")?.takeIf { it.isNotBlank() }?.let(::add)
                        extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()?.let(::add)
                        extras
                            .getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
                            ?.take(4)
                            ?.forEach { add(it.toString()) }
                        if (n.packageName == "com.ubercab" && journey(n))
                            addAll(rideText(s.context, note))
                    }
                        .distinct()
                }
                .getOrDefault(emptyList())
        }
    details.forEach {
        Text(it.take(180), maxLines = 2, style = MaterialTheme.typography.bodyMedium)
    }
    val icon =
        remember(n.key, n.postTime) {
            runCatching {
                val tracker =
                    if (Build.VERSION.SDK_INT >= 33)
                        extras.getParcelable("android.progressTrackerIcon", Icon::class.java)
                    else null
                (tracker ?: note.getLargeIcon())
                    ?.loadDrawable(s.context)
                    ?.toBitmap(72, 72)
                    ?.asImageBitmap()
            }
                .getOrNull()
        }
    icon?.let { Image(it, "Activity artwork", Modifier.size(42.dp)) }
    val segments =
        remember(n.key, n.postTime) {
            if (Build.VERSION.SDK_INT >= 33)
                runCatching {
                        extras
                            .getParcelableArrayList("android.progressSegments", Bundle::class.java)
                            .orEmpty()
                            .take(32)
                            .map { it.getInt("length") to it.getInt("colorInt") }
                            .filter { it.first > 0 }
                    }
                    .getOrDefault(emptyList())
            else emptyList()
        }
    if (segments.isNotEmpty())
        Row(Modifier.fillMaxWidth().height(5.dp)) {
            segments.forEach { (amount, color) ->
                Spacer(
                    Modifier.fillMaxHeight()
                        .weight(amount.toFloat())
                        .background(
                            if (color == 0) MaterialTheme.colorScheme.primary else Color(color),
                        ),
                )
            }
        }
}

/**
 * Uber's trip notification may keep the plate and model only in its RemoteViews. Read those two
 * fields locally and fall back to the standard notification text when its layout changes.
 */
@Suppress("DEPRECATION")
private fun rideText(c: Context, n: Notification): List<String> {
    val views = n.bigContentView ?: n.contentView ?: return emptyList()
    if (views.`package` != "com.ubercab") return emptyList()
    val root = views.apply(c, FrameLayout(c))
    val entries = ArrayDeque<View>()
    entries.add(root)
    val result = mutableListOf<String>()
    var seen = 0
    while (entries.isNotEmpty() && seen++ < 180) {
        val v = entries.removeFirst()
        if (v is ViewGroup) for (i in 0 until v.childCount) entries.add(v.getChildAt(i))
        if (v is TextView && v.id != View.NO_ID) {
            val name = runCatching { v.resources.getResourceEntryName(v.id) }.getOrDefault("")
            if (name.endsWith("_vehicle_model") || name.endsWith("_vehicle_plate"))
                result += v.text.toString()
        }
    }
    return result
}
