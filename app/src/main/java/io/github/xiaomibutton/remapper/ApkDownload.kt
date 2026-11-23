package io.github.xiaomibutton.remapper

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.URL
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection

internal class UpdateIntegrityException : IOException()

internal fun downloadApk(release: ApkRelease, output: OutputStream, progress: (Int) -> Unit) {
    var url = URL(releaseApkUrl(release.version))
    repeat(5) {
        val connection = (url.openConnection() as HttpsURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = false
            setRequestProperty("Accept-Encoding", "identity")
        }
        try {
            when (val status = connection.responseCode) {
                200 -> {
                    connection.inputStream.use { copyVerifiedApk(it, output, release, progress) }
                    return
                }
                301, 302, 303, 307, 308 -> {
                    val location = connection.getHeaderField("Location") ?: throw IOException("Missing redirect")
                    url = URL(url, location)
                    if (url.protocol != "https" || url.userInfo != null ||
                        (url.host != "github.com" && !url.host.endsWith(".githubusercontent.com"))
                    ) throw IOException("Unexpected release host")
                }
                else -> throw IOException("HTTP $status")
            }
        } finally {
            connection.disconnect()
        }
    }
    throw IOException("Too many redirects")
}

internal fun copyVerifiedApk(input: InputStream, output: OutputStream, release: ApkRelease, progress: (Int) -> Unit) {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(64 * 1024)
    var received = 0L
    var lastPercent = -1
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        received += count
        if (received > release.size) throw UpdateIntegrityException()
        output.write(buffer, 0, count)
        digest.update(buffer, 0, count)
        val percent = (100.0 * received / release.size).toInt()
        if (percent != lastPercent) {
            progress(percent)
            lastPercent = percent
        }
    }
    if (received != release.size || digest.digest().toHexString() != release.sha256) throw UpdateIntegrityException()
}
