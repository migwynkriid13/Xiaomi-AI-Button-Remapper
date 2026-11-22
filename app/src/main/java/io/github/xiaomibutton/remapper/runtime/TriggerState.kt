package io.github.xiaomibutton.remapper.runtime

import android.app.KeyguardManager
import android.content.Context
import android.content.res.Configuration
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.PowerManager
import io.github.xiaomibutton.remapper.TriggerCondition

internal class TriggerState(private val context: Context) {
    fun allows(condition: TriggerCondition): Boolean = when (condition) {
        TriggerCondition.ALWAYS -> true
        TriggerCondition.SCREEN_ON, TriggerCondition.SCREEN_OFF ->
            context.getSystemService(PowerManager::class.java)?.isInteractive.let {
                it == (condition == TriggerCondition.SCREEN_ON)
            }
        TriggerCondition.UNLOCKED, TriggerCondition.LOCKED ->
            context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked.let {
                it == (condition == TriggerCondition.LOCKED)
            }
        TriggerCondition.MEDIA_PLAYING, TriggerCondition.MEDIA_IDLE ->
            context.getSystemService(AudioManager::class.java)?.isMusicActive.let {
                it == (condition == TriggerCondition.MEDIA_PLAYING)
            }
        TriggerCondition.WIFI_CONNECTED, TriggerCondition.WIFI_DISCONNECTED -> {
            val network = context.getSystemService(ConnectivityManager::class.java)
            val usesWifi = network?.let { it.getNetworkCapabilities(it.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ?: false }
            usesWifi == (condition == TriggerCondition.WIFI_CONNECTED)
        }
        TriggerCondition.CHARGING, TriggerCondition.NOT_CHARGING ->
            context.getSystemService(BatteryManager::class.java)?.isCharging.let {
                it == (condition == TriggerCondition.CHARGING)
            }
        TriggerCondition.PORTRAIT -> context.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        TriggerCondition.LANDSCAPE -> context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        TriggerCondition.HEADPHONES_CONNECTED, TriggerCondition.HEADPHONES_DISCONNECTED ->
            context.getSystemService(AudioManager::class.java)?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)?.any {
                it.type in headphones
            }.let { it == (condition == TriggerCondition.HEADPHONES_CONNECTED) }
    }

    private companion object {
        val headphones = setOf(AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_HEARING_AID)
    }
}
