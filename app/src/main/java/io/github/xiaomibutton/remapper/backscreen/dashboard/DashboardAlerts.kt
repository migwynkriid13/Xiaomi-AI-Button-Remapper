package io.github.xiaomibutton.remapper.backscreen.dashboard

import android.app.Notification
import android.content.*
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.*
import android.provider.Settings
import android.service.notification.StatusBarNotification
import android.view.*
import android.widget.TextView
import io.github.xiaomibutton.remapper.MediaSessionListener
import io.github.xiaomibutton.remapper.R
import io.github.xiaomibutton.remapper.backscreen.core.*
import io.github.xiaomibutton.remapper.backscreen.rear.BackSensor

internal object DashboardAlerts {
    var urgent: ((String) -> Unit)? = null
    private var banner: View? = null
    private var manager: WindowManager? = null
    private val handler = Handler(Looper.getMainLooper())
    private val expire = Runnable { clear() }

    fun clear() {
        handler.removeCallbacks(expire)
        banner?.let { view -> runCatching { manager?.removeViewImmediate(view) } }
        banner = null
        manager = null
    }

    fun posted(listener: MediaSessionListener, n: StatusBarNotification) {
        if (!listener.getSharedPreferences(DashboardStore.FILE, 0).getBoolean("enabled", false))
            return
        val s = DashboardStore(listener.applicationContext)
        if (n.user != Process.myUserHandle() || n.packageName == listener.packageName) return
        val urgentNote =
            n.notification.category in
                setOf(Notification.CATEGORY_CALL, Notification.CATEGORY_ALARM)
        val over =
            s.flag("Banners over rear apps") &&
                Settings.canDrawOverlays(listener) &&
                RearState.owner(listener) is RearOwner.Lent
        if (!over && (!urgentNote || !s.flag("Calls and alarms", true))) return
        if (!permitted(listener, n)) return
        if (urgentNote && s.flag("Calls and alarms", true) && Page.LIVE in s.pages()) {
            BackSensor.check(listener) { covered ->
                if (!covered &&
                    runCatching { listener.getActiveNotifications(arrayOf(n.key)).isNotEmpty() }
                        .getOrDefault(false)) {
                    urgent?.invoke(n.key)
                    KeeperService.instance?.wakeDashboardAlert()
                }
            }
        }
        if (!over || PhoneLock.isLocked(listener)) return
        val display = BackScreen.findRearDisplay(listener) ?: return
        val ctx =
            listener
                .createDisplayContext(display)
                .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
        val wm = ctx.getSystemService(WindowManager::class.java)
        // The small overlay only names the originating app, so a lock transition cannot expose a
        // message.
        val view =
            TextView(ctx).apply {
                setTextColor(ctx.getColor(R.color.remapper_on_surface))
                text = appLabel(s, n.packageName).take(48)
                setPadding(22, 9, 22, 9)
                background =
                    GradientDrawable().apply {
                        setColor(ctx.getColor(R.color.remapper_surface_container))
                        cornerRadius = 32f
                    }
                maxLines = 1
            }
        val params =
            WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT,
            )
        params.gravity = Gravity.TOP or Gravity.END
        clear()
        runCatching { wm.addView(view, params) }
            .onSuccess {
                banner = view
                manager = wm
                handler.postDelayed(expire, 2500)
            }
    }
}
