package io.github.xiaomibutton.remapper.backscreen.dashboard

import android.content.Context
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.net.URL
import java.net.URLEncoder
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.net.ssl.HttpsURLConnection
import org.json.JSONObject

internal object Secrets {
    private const val KEY = "rear_dashboard_home"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return ks.getKey(KEY, null) as? SecretKey
            ?: KeyGenerator.getInstance("AES", "AndroidKeyStore").run {
                init(
                    KeyGenParameterSpec.Builder(
                            KEY,
                            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                        )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .build(),
                )
                generateKey()
            }
    }

    fun save(context: Context, token: String) {
        val cipher =
            Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val payload = cipher.iv + cipher.doFinal(token.trim().toByteArray())
        val s = DashboardStore(context)
        s.prefs
            .edit()
            .putString("tokenHost", s.value("haBase").trimEnd('/'))
            .putString("credential", Base64.encodeToString(payload, Base64.NO_WRAP))
            .apply()
    }

    fun read(s: DashboardStore): String? = runCatching {
        require(s.value("tokenHost") == s.value("haBase").trimEnd('/'))
        val payload = Base64.decode(s.value("credential"), Base64.NO_WRAP)
        val cipher =
            Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, payload.copyOfRange(0, 12)))
            }
        String(cipher.doFinal(payload.copyOfRange(12, payload.size)), Charsets.UTF_8)
    }
        .getOrNull()

    fun clear(context: Context) {
        DashboardStore(context).prefs.edit().remove("credential").remove("tokenHost").apply()
    }
}

internal fun java.io.InputStream.limitedBytes(limit: Int): ByteArray {
    val result = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val n = read(buffer, 0, minOf(buffer.size, limit - result.size() + 1))
        if (n < 0) return result.toByteArray()
        require(result.size() + n <= limit) { "Feed is larger than 256 KB" }
        result.write(buffer, 0, n)
    }
}

internal fun query(value: String) = URLEncoder.encode(value, "UTF-8")

internal fun webJson(url: String, token: String? = null, post: JSONObject? = null): JSONObject =
    requestJson(url, token, post) as? JSONObject ?: error("Expected a JSON object")

internal fun homeEntities(s: DashboardStore): List<String> {
    val server = s.value("haBase").trimEnd('/')
    val data =
        requestJson(
            server + "/api/states",
            Secrets.read(s) ?: error("Save a token for this server"),
            null,
        )
            as? org.json.JSONArray ?: error("Expected a list of entities")
    return List(data.length()) { data.optJSONObject(it)?.optString("entity_id").orEmpty() }
        .filter { it.isNotBlank() }
        .sorted()
}

private fun requestJson(url: String, token: String?, post: JSONObject?): Any {
    val endpoint = URL(url)
    require(endpoint.protocol == "https" && endpoint.userInfo == null) { "Enter an HTTPS address" }
    val connection = endpoint.openConnection() as HttpsURLConnection
    return try {
        connection.connectTimeout = 6000
        connection.readTimeout = 7000
        connection.instanceFollowRedirects = false
        connection.setRequestProperty("User-Agent", "Xiaomi-AI-Button-Remapper/1")
        token?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
        if (post != null) {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(post.toString().toByteArray()) }
        }
        check(connection.responseCode in 200..299) { "Server returned ${connection.responseCode}" }
        val bytes = connection.inputStream.use { it.limitedBytes(262144) }
        require(bytes.size <= 262144) { "Feed is larger than 256 KB" }
        if (post != null) JSONObject()
        else org.json.JSONTokener(String(bytes, Charsets.UTF_8)).nextValue()
    } finally {
        connection.disconnect()
    }
}

internal data class Weather(val label: String, val code: Int)

internal fun weather(s: DashboardStore): Weather {
    val place = s.value("city")
    val c = s.context
    val known =
        if (
            s.flag("deviceWeather") &&
                c.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            val manager = c.getSystemService(android.location.LocationManager::class.java)
            manager
                .getProviders(true)
                .mapNotNull { provider ->
                    runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
                }
                .maxByOrNull { it.time }
        } else null
    val found =
        if (known != null)
            JSONObject()
                .put("latitude", known.latitude)
                .put("longitude", known.longitude)
                .put("name", "Nearby")
        else {
            require(place.isNotBlank()) { "Choose a city or allow approximate location" }
            webJson("https://geocoding-api.open-meteo.com/v1/search?count=1&name=" + query(place))
                .optJSONArray("results")
                ?.optJSONObject(0) ?: error("City not found")
        }
    val unit = if (s.value("units") == "°F") "fahrenheit" else "celsius"
    val result =
        webJson(
            "https://api.open-meteo.com/v1/forecast?latitude=${found.getDouble("latitude")}&longitude=${found.getDouble("longitude")}&current=temperature_2m,weather_code&temperature_unit=$unit",
        )
    val current = result.getJSONObject("current")
    val code = current.getInt("weather_code")
    val sky =
        when (code) {
            0 -> "Clear"
            in 1..3 -> "Cloudy"
            45,
            48 -> "Fog"
            in 71..77,
            85,
            86 -> "Snow"
            in 95..99 -> "Storm"
            else -> "Rain"
        }
    return Weather(
        "${current.getDouble("temperature_2m").toInt()}${s.value("units","°C")} · $sky · ${found.getString("name")}",
        code,
    )
}

internal fun entity(s: DashboardStore, activate: Boolean = false): String {
    val base = s.value("haBase").trimEnd('/')
    val name = s.value("entity", "light.living_room")
    require(name.matches(Regex("[a-z0-9_]+\\.[a-z0-9_]+"))) { "Invalid entity ID" }
    val token = Secrets.read(s) ?: error("Save a token for this server")
    if (activate) {
        val service = s.value("service", "homeassistant.toggle")
        require(service.matches(Regex("[a-z0-9_]+\\.[a-z0-9_]+"))) { "Invalid service" }
        webJson(
            "$base/api/services/" + service.replace('.', '/'),
            token,
            JSONObject().put("entity_id", name),
        )
    }
    val state = webJson("$base/api/states/$name", token)
    val attr = state.optJSONObject("attributes")
    return "${attr?.optString("friendly_name",name) ?: name} · ${state.getString("state")} ${attr?.optString("unit_of_measurement").orEmpty()}"
        .trim()
}

internal fun feedValue(source: JSONObject, path: String): String {
    var node: Any = source
    for (part in path.split('.')) node =
        when (val value = node) {
            is JSONObject -> value.get(part)
            is org.json.JSONArray -> value.get(part.toInt())
            else -> error("Value path does not exist")
        }
    require(node is String || node is Number || node is Boolean) {
        "Select a text or numeric field"
    }
    return node.toString().take(180)
}

internal data class RingReading(val percent: Float, val reset: Long?)

internal fun usage(s: DashboardStore, index: Int): RingReading {
    val uri = s.value("usageFile$index")
    val root =
        if (uri.isBlank()) webJson(s.value("usageUrl$index"))
        else {
            val bytes =
                s.context.contentResolver.openInputStream(Uri.parse(uri))?.use {
                    it.limitedBytes(262144)
                } ?: error("Open the file again")
            require(bytes.size <= 262144)
            JSONObject(String(bytes, Charsets.UTF_8))
        }
    return parseUsage(root, s.value("ring$index", "Session"))
}

internal fun parseUsage(root: JSONObject, window: String): RingReading {
    val rates = root.optJSONObject("rate_limits") ?: root
    val keys =
        when (window) {
            "Weekly" -> listOf("seven_day")
            "Scoped weekly" -> listOf("seven_day_scoped", "seven_day_sonnet", "seven_day_opus")
            else -> listOf("five_hour")
        }
    val period =
        keys.firstNotNullOfOrNull { rates.optJSONObject(it) } ?: error("No reading for this window")
    val percent = period.optDouble("used_percentage", period.optDouble("utilization"))
    require(percent.isFinite()) { "No usage percentage for this window" }
    val timestamp = period.optString("resets_at")
    val reset =
        timestamp
            .toLongOrNull()
            ?.takeIf { it > 0 }
            ?.let {
                if (it < 100000000000L) it * 1000 else it
            } ?: runCatching { java.time.Instant.parse(timestamp).toEpochMilli() }.getOrNull()
    return RingReading(percent.toFloat().coerceIn(0f, 100f), reset)
}
