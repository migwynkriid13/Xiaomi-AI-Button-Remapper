package io.github.xiaomibutton.remapper

import android.content.Context
import android.content.ComponentName
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.ResolveInfo
import android.content.pm.ShortcutInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.LruCache
import android.util.AtomicFile
import android.os.Process
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.min
import kotlin.math.roundToInt
import java.io.File
import java.security.MessageDigest

internal sealed interface PickerIconSource {
    val key: String
    val packageName: String

    data class Activity(val activity: ResolveInfo) : PickerIconSource {
        override val packageName: String get() = activity.activityInfo.packageName
        override val key: String get() = "activity/" + packageName + "/" + activity.activityInfo.name
    }

    data class Service(val service: ResolveInfo) : PickerIconSource {
        override val packageName: String get() = service.serviceInfo.packageName
        override val key: String get() = "service/" + packageName + "/" + service.serviceInfo.name
    }

    data class ServiceComponent(val component: ComponentName) : PickerIconSource {
        override val packageName: String get() = component.packageName
        override val key: String get() = "service-component/" + component.flattenToShortString()
    }

    data class Shortcut(val shortcut: ShortcutInfo) : PickerIconSource {
        override val packageName: String get() = shortcut.`package`
        override val key: String get() = "shortcut/" + shortcut.userHandle + "/" + packageName + "/" + shortcut.id
    }

    data class Package(override val packageName: String) : PickerIconSource {
        override val key = "package/" + packageName
    }

    data class Hosted(override val packageName: String, val id: String) : PickerIconSource {
        override val key = "saved-shortcut/" + packageName + "/" + id
    }

    data class Legacy(val uri: String) : PickerIconSource {
        override val packageName = ""
        override val key = "legacy/" + uri
    }
}

private val iconWorkers = Dispatchers.IO.limitedParallelism(3)

/** Scoped to the open picker. Device density and appearance changes drop the cache. */
internal class PickerIconCache : LruCache<String, Bitmap>(2 * 1024 * 1024) {
    override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
}

private fun render(drawable: Drawable?, side: Int): Bitmap? {
    if (drawable == null) return null
    val image = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
    val width = drawable.intrinsicWidth.takeIf { it > 0 } ?: side
    val height = drawable.intrinsicHeight.takeIf { it > 0 } ?: side
    val scale = min(side.toFloat() / width, side.toFloat() / height)
    val w = (width * scale).roundToInt().coerceIn(1, side)
    val h = (height * scale).roundToInt().coerceIn(1, side)
    val left = (side - w) / 2
    val top = (side - h) / 2
    drawable.mutate().apply {
        setBounds(left, top, left + w, top + h)
        draw(Canvas(image))
    }
    return image
}

private fun thumbnail(context: Context, uri: String): File {
    val hash = MessageDigest.getInstance("SHA-256").digest(uri.toByteArray()).joinToString("") { "%02x".format(it) }
    return File(File(context.cacheDir, "shortcut-icons"), hash + ".png")
}

/** Some creators return contact artwork available only in the result. Store a small private thumbnail. */
@Suppress("DEPRECATION")
internal fun rememberShortcutArtwork(context: Context, result: Intent, uri: String, creator: String?) {
    val bitmap = result.getParcelableExtra<Bitmap>(Intent.EXTRA_SHORTCUT_ICON)
    val resource = result.getParcelableExtra<Intent.ShortcutIconResource>(Intent.EXTRA_SHORTCUT_ICON_RESOURCE)
    val pm = context.packageManager
    val drawable = when {
        bitmap != null -> BitmapDrawable(context.resources, bitmap)
        resource != null -> {
            val res = pm.getResourcesForApplication(resource.packageName)
            val id = res.getIdentifier(resource.resourceName, null, null)
            if (id != 0) res.getDrawable(id, null) else null
        }
        creator != null -> ComponentName.unflattenFromString(creator)?.let(pm::getActivityIcon)
        else -> null
    }
    val image = render(drawable, 128) ?: return
    val file = thumbnail(context, uri)
    val directory = requireNotNull(file.parentFile)
    directory.mkdirs()
    val atomic = AtomicFile(file)
    val output = atomic.startWrite()
    try {
        check(image.compress(Bitmap.CompressFormat.PNG, 100, output))
        atomic.finishWrite(output)
    } catch (e: Exception) {
        atomic.failWrite(output)
        throw e
    }
    directory.listFiles { f -> f.extension == "png" }?.sortedByDescending { it.lastModified() }?.drop(32)?.forEach { it.delete() }
}

private fun loadIcon(context: Context, source: PickerIconSource, dpi: Int, pixels: Int): Bitmap? {
    val pm = context.packageManager
    // Drawing the original resource preserves its adaptive mask, colors, and any resource qualifiers.
    val icon = try {
        val drawable = when (source) {
            is PickerIconSource.Activity -> source.activity.loadIcon(pm)
            is PickerIconSource.Service -> source.service.loadIcon(pm)
            is PickerIconSource.ServiceComponent -> {
                @Suppress("DEPRECATION")
                pm.getServiceInfo(source.component, 0).loadIcon(pm)
            }
            is PickerIconSource.Shortcut -> context.getSystemService(LauncherApps::class.java)
                .getShortcutBadgedIconDrawable(source.shortcut, dpi)
            is PickerIconSource.Package -> pm.getLaunchIntentForPackage(source.packageName)?.resolveActivityInfo(pm, 0)
                ?.loadIcon(pm) ?: pm.getApplicationIcon(source.packageName)
            is PickerIconSource.Legacy -> {
                val file = thumbnail(context, source.uri)
                val cached = if (file.isFile) BitmapFactory.decodeFile(file.path) else null
                if (cached != null) BitmapDrawable(context.resources, cached) else
                    Intent.parseUri(source.uri, Intent.URI_INTENT_SCHEME).resolveActivityInfo(pm, 0)?.loadIcon(pm)
            }
            is PickerIconSource.Hosted -> {
                val launcher = context.getSystemService(LauncherApps::class.java)
                if (!launcher.hasShortcutHostPermission()) null else {
                    val flags = LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED or
                        LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST
                    val query = LauncherApps.ShortcutQuery().setPackage(source.packageName).setShortcutIds(listOf(source.id)).setQueryFlags(flags)
                    launcher.getShortcuts(query, Process.myUserHandle())?.firstOrNull()?.let { launcher.getShortcutBadgedIconDrawable(it, dpi) }
                }
            }
        }
        render(drawable, pixels)
    } catch (_: Exception) { null } // A package can change or shortcut access can expire while scrolling.
    if (icon != null) return icon
    return try { render(pm.getApplicationIcon(source.packageName), pixels) } catch (_: Exception) {
        try { render(pm.defaultActivityIcon, pixels) } catch (_: RuntimeException) { null }
    }
}

@Composable
internal fun PickerIcon(source: PickerIconSource, cache: PickerIconCache) {
    val context = LocalContext.current
    val density = LocalConfiguration.current.densityDpi
    val side = 40.dp
    val pixels = with(LocalDensity.current) { side.roundToPx() }.coerceIn(1, 256)
    val key = source.key + "/" + pixels
    var image by remember(cache, key) { mutableStateOf(cache.get(key)) }
    LaunchedEffect(cache, key) {
        if (image == null) image = withContext(iconWorkers) {
            cache.get(key) ?: loadIcon(context, source, density, pixels)?.also { cache.put(key, it) }
        }
    }
    val bitmap = image
    if (bitmap != null) {
        Image(bitmap.asImageBitmap(), contentDescription = null, modifier = Modifier.size(side))
    } else Box(Modifier.size(side).clip(TileShape).background(MaterialTheme.colorScheme.surfaceContainerLow),
        contentAlignment = Alignment.Center) {
        LineIcon(Mark.BUTTON, Modifier.size(21.dp), MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
