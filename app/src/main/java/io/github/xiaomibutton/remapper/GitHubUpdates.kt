package io.github.xiaomibutton.remapper

import java.io.IOException
import java.io.Serializable
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject

private const val REPOSITORY = "migwynkriid13/Xiaomi-AI-Button-Remapper"
private const val LATEST_RELEASE = "https://api.github.com/repos/$REPOSITORY/releases/latest"
private const val APK_NAME = "Xiaomi-AI-Button-Remapper.apk"

private val stableVersion = Regex("""(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)""")
private val assetDigest = Regex("sha256:([a-f0-9]{64})")

internal data class ApkRelease(val version: String, val size: Long, val sha256: String) : Serializable

private fun versionParts(version: String): List<Int>? {
    val match = stableVersion.matchEntire(version) ?: return null
    return match.groupValues.drop(1).map { it.toIntOrNull() ?: return null }
}

internal fun releaseApkUrl(version: String): String =
    "https://github.com/$REPOSITORY/releases/download/v$version/$APK_NAME"

internal fun newerGitHubRelease(release: JSONObject, installedVersion: String): ApkRelease? {
    if (release.optBoolean("draft", true) || release.optBoolean("prerelease", true)) return null
    val tag = release.optString("tag_name")
    if (!tag.startsWith("v")) return null
    val version = tag.removePrefix("v")
    val available = versionParts(version) ?: return null
    val installed = versionParts(installedVersion) ?: return null
    val difference = available.zip(installed).firstOrNull { (next, current) -> next != current }
    if (difference == null || difference.first < difference.second) return null

    val assets = release.optJSONArray("assets") ?: return null
    val expectedUrl = releaseApkUrl(version)
    for (index in 0 until assets.length()) {
        val asset = assets.optJSONObject(index) ?: continue
        if (asset.optString("name") == APK_NAME && asset.optString("state") == "uploaded" &&
            asset.optString("browser_download_url") == expectedUrl
        ) {
            val size = asset.optLong("size")
            val digest = assetDigest.matchEntire(asset.optString("digest")) ?: continue
            if (size > 0) return ApkRelease(version, size, digest.groupValues[1])
        }
    }
    return null
}

internal suspend fun checkForGitHubUpdate(installedVersion: String): ApkRelease? = withContext(Dispatchers.IO) {
    val connection = (URL(LATEST_RELEASE).openConnection() as HttpsURLConnection).apply {
        connectTimeout = 5000
        readTimeout = 5000
        instanceFollowRedirects = false
        setRequestProperty("Accept", "application/vnd.github+json")
        setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        setRequestProperty("User-Agent", APK_NAME.removeSuffix(".apk"))
    }
    try {
        if (connection.responseCode != HttpsURLConnection.HTTP_OK) return@withContext null
        val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        newerGitHubRelease(JSONObject(body), installedVersion)
    } catch (_: IOException) {
        null
    } catch (_: JSONException) {
        null
    } finally {
        connection.disconnect()
    }
}
