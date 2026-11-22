package io.github.xiaomibutton.remapper.backscreen

import io.github.xiaomibutton.remapper.R
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import io.github.xiaomibutton.remapper.backscreen.battery.BatteryFragment
import io.github.xiaomibutton.remapper.backscreen.camera.CameraFragment
import io.github.xiaomibutton.remapper.backscreen.core.BackScreen
import io.github.xiaomibutton.remapper.backscreen.core.KeeperService
import io.github.xiaomibutton.remapper.backscreen.core.RearCommands
import io.github.xiaomibutton.remapper.backscreen.core.ShizukuState
import io.github.xiaomibutton.remapper.backscreen.core.isRearScreenPlatform
import io.github.xiaomibutton.remapper.backscreen.mirror.MirrorFragment
import io.github.xiaomibutton.remapper.backscreen.notifications.NotificationsFragment
import io.github.xiaomibutton.remapper.backscreen.ui.AppDialogs
import io.github.xiaomibutton.remapper.backscreen.ui.DiagnosticsDialog
import io.github.xiaomibutton.remapper.backscreen.ui.Feature
import io.github.xiaomibutton.remapper.backscreen.ui.Refreshable
import io.github.xiaomibutton.remapper.backscreen.ui.SetupHelpDialog
import io.github.xiaomibutton.remapper.backscreen.wallpaper.WallpaperFragment
import io.github.xiaomibutton.remapper.backscreen.wallpaper.WallpaperSettings
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.navigation.NavigationBarView
import com.google.android.material.snackbar.Snackbar
import rikka.shizuku.Shizuku

/**
 * The app: a top bar with the tab's help (?) and the menu, a banner while Shizuku isn't ready,
 * and a bottom bar with a tab per section (a rail down the side in landscape).
 */
class RearScreenActivity : AppCompatActivity() {

    private lateinit var nav: NavigationBarView
    private lateinit var banner: View
    private lateinit var bannerTitle: TextView
    private lateinit var bannerText: TextView
    private lateinit var bannerButton: Button
    private lateinit var helpItem: MenuItem

    private var current = Feature.WALLPAPER
    private var createdAt = 0L

    private val refresher = object : Runnable {
        override fun run() {
            refresh()
            BackScreen.mainHandler.postDelayed(this, REFRESH_MS)
        }
    }

    /**
     * Shizuku isn't ready, and has had its moment to connect since the app started: the banner
     * shows, and the tabs that need it say they're waiting for it. As of the last refresh.
     */
    var shizukuMissing = false
        private set

    private val permissionListener = Shizuku.OnRequestPermissionResultListener { request, result ->
        if (request != REQUEST_SHIZUKU) return@OnRequestPermissionResultListener
        val granted = result == PackageManager.PERMISSION_GRANTED
        BackScreen.log(if (granted) "Shizuku access granted" else "Shizuku access denied")
        // It may not ask again, but Shizuku's own app can still allow it.
        if (!granted) snackbar(R.string.rear_shizuku_denied, Snackbar.LENGTH_LONG, R.string.rear_open_shizuku, ::openShizuku)
        refresh()
    }

    private val binderListener = Shizuku.OnBinderReceivedListener { BackScreen.mainHandler.post { refresh() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!isRearScreenPlatform()) {
            finish()
            return
        }
        enableEdgeToEdge()
        setContentView(R.layout.rear_activity_main)
        // For the banner's grace: kept through turning the phone, so the tabs don't go back to
        // waiting a moment for Shizuku each time, but not into a new process.
        createdAt = savedInstanceState?.getLong(KEY_CREATED_AT)?.takeIf { it >= Process.getStartUptimeMillis() }
            ?: SystemClock.uptimeMillis()

        val toolbar = findViewById<MaterialToolbar>(R.id.rear_toolbar)
        toolbar.setNavigationIcon(R.drawable.action_arrow_back)
        toolbar.navigationContentDescription = getString(R.string.rear_back)
        toolbar.setNavigationOnClickListener { finish() }
        nav = findViewById(R.id.rear_nav)
        banner = findViewById(R.id.rear_shizukuBanner)
        bannerTitle = findViewById(R.id.rear_shizukuBannerTitle)
        bannerText = findViewById(R.id.rear_shizukuBannerText)
        bannerButton = findViewById(R.id.rear_shizukuBannerButton)

        // Drawn edge to edge: the top bar keeps clear of the status bar, and the bottom bar of
        // the navigation bar by itself, its colour running on behind the buttons with no scrim.
        // In landscape the rail keeps clear of both by itself, and the section of the navigation bar.
        window.isNavigationBarContrastEnforced = false
        val content = findViewById<View>(R.id.rear_content)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.rear_root)) { root, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            toolbar.setPadding(0, bars.top, 0, 0)
            root.setPadding(bars.left, 0, bars.right, 0)
            content.setPadding(0, 0, 0, if (nav is BottomNavigationView) 0 else bars.bottom)
            insets
        }

        helpItem = toolbar.menu.findItem(R.id.rear_menu_help)
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.rear_menu_help -> AppDialogs.help(this, current)
                R.id.rear_menu_refresh -> refreshBackScreen()
                R.id.rear_menu_diagnostics -> DiagnosticsDialog.show(supportFragmentManager)
                R.id.rear_menu_setup_help -> showSetupHelp()
                R.id.rear_menu_about -> AppDialogs.about(this)
                else -> return@setOnMenuItemClickListener false
            }
            true
        }

        current = (savedInstanceState?.getString(KEY_TAB) ?: intent.getStringExtra(KEY_TAB))
            ?.let { name -> Feature.entries.firstOrNull { it.name == name } } ?: Feature.WALLPAPER
        nav.selectedItemId = current.navId
        showTab(current)
        nav.setOnItemSelectedListener { item ->
            Feature.forNavId(item.itemId)?.let(::showTab)
            true
        }

        Shizuku.addRequestPermissionResultListener(permissionListener)
        Shizuku.addBinderReceivedListenerSticky(binderListener)
        if (savedInstanceState == null) handleIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_TAB, current.name)
        outState.putLong(KEY_CREATED_AT, createdAt)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    /**
     * The wallpaper's tile opens us to toggle when it isn't allowed to itself, the Quick Switch
     * tile when the service wasn't running, and the keeper's notification when the wallpaper's
     * folder needs choosing again; only through our private alias ([LaunchCommand.ALIAS]). Each is
     * done once: afterwards the intent is a plain one.
     */
    private fun handleIntent(intent: Intent?) {
        intent ?: return
        when (LaunchCommand.of(intent.action, intent.flags, intent.component?.className)) {
            LaunchCommand.TOGGLE -> toggleWallpaper()
            LaunchCommand.QUICK_SWITCH -> {
                // We're in front now, so there's nothing to send; start the service for the next tap.
                selectTab(Feature.MIRROR)
                KeeperService.start(this, KeeperService.ACTION_UPDATE)
                snackbar(R.string.rear_qs_was_asleep, Snackbar.LENGTH_LONG)
            }
            LaunchCommand.SHOW_WALLPAPER -> selectTab(Feature.WALLPAPER)
            null -> return
        }
        setIntent(Intent(intent).setAction(Intent.ACTION_MAIN))
    }

    private fun selectTab(feature: Feature) {
        nav.selectedItemId = feature.navId
    }

    private fun toggleWallpaper() {
        val on = !WallpaperSettings.isEnabled(this)
        if (on && !RearCommands.isReady()) {
            needShizuku()
            return
        }
        KeeperService.setWallpaper(this, on)
    }

    override fun onResume() {
        super.onResume()
        if (!isRearScreenPlatform()) return
        resumeRearScreen(this)
        BackScreen.mainHandler.post(refresher)
    }

    override fun onPause() {
        BackScreen.mainHandler.removeCallbacks(refresher)
        super.onPause()
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(permissionListener)
        Shizuku.removeBinderReceivedListener(binderListener)
        super.onDestroy()
    }

    /** Shows [feature]'s tab. Each is made the first time it's shown, then kept, so it keeps its place. */
    private fun showTab(feature: Feature) {
        current = feature
        findViewById<MaterialToolbar>(R.id.rear_toolbar).setTitle(feature.label)
        // TalkBack and the long-press tooltip name the section: "Help with Mirror".
        val help = getString(R.string.rear_help_with, getString(feature.label))
        helpItem.contentDescription = help
        helpItem.tooltipText = help
        val fm = supportFragmentManager
        val tx = fm.beginTransaction().setReorderingAllowed(true)
        for (other in Feature.entries) {
            if (other != feature) fm.findFragmentByTag(other.name)?.let { tx.hide(it) }
        }
        val fragment = fm.findFragmentByTag(feature.name)
        if (fragment == null) tx.add(R.id.rear_content, newTab(feature), feature.name) else tx.show(fragment)
        tx.commitNow()
        refresh()
    }

    private fun newTab(feature: Feature): Fragment = when (feature) {
        Feature.WALLPAPER -> WallpaperFragment()
        Feature.NOTIFICATIONS -> NotificationsFragment()
        Feature.BATTERY -> BatteryFragment()
        Feature.MIRROR -> MirrorFragment()
        Feature.CAMERA -> CameraFragment()
    }

    private fun refresh() {
        refreshBanner()
        (supportFragmentManager.findFragmentByTag(current.name) as? Refreshable)?.refresh()
    }

    /**
     * The banner, while Shizuku isn't ready, with the one thing to do about it. Its connection
     * arrives just after the app starts, so wait a moment before saying it isn't there.
     */
    private fun refreshBanner() {
        val state = ShizukuState.read(this)
        val running = state == ShizukuState.NOT_ALLOWED || state == ShizukuState.BLOCKED
        val settled = SystemClock.uptimeMillis() - createdAt > BANNER_GRACE_MS
        shizukuMissing = state != ShizukuState.READY && (running || settled)
        banner.visibility = if (shizukuMissing) View.VISIBLE else View.GONE
        val (title, text, button) = when (state) {
            ShizukuState.READY -> return
            ShizukuState.NOT_ALLOWED -> Triple(R.string.rear_shizuku_banner_not_allowed, R.string.rear_shizuku_banner_not_allowed_text, R.string.rear_allow_access)
            ShizukuState.BLOCKED -> Triple(R.string.rear_shizuku_banner_blocked, R.string.rear_shizuku_banner_blocked_text, R.string.rear_open_shizuku)
            ShizukuState.NOT_RUNNING -> Triple(R.string.rear_shizuku_banner_not_running, R.string.rear_shizuku_banner_not_running_text, R.string.rear_open_shizuku)
            ShizukuState.NOT_INSTALLED -> Triple(R.string.rear_shizuku_banner_not_installed, R.string.rear_shizuku_banner_not_installed_text, R.string.rear_get_shizuku)
        }
        bannerTitle.setText(title)
        bannerText.setText(text)
        bannerButton.setText(button)
        bannerButton.setOnClickListener { fixShizuku(state) }
    }

    /** What the banner's and Setup help's Shizuku button does for [state]. */
    fun fixShizuku(state: ShizukuState) {
        when (state) {
            ShizukuState.READY -> {}
            ShizukuState.NOT_ALLOWED -> requestShizuku()
            ShizukuState.BLOCKED, ShizukuState.NOT_RUNNING -> openShizuku()
            ShizukuState.NOT_INSTALLED -> getShizuku()
        }
    }

    /**
     * Shizuku's own prompt to allow access. After "Deny and don't ask again" it would refuse
     * without a word, so this says where to allow it instead.
     */
    private fun requestShizuku() {
        when (ShizukuState.read(this)) {
            ShizukuState.NOT_ALLOWED -> try {
                Shizuku.requestPermission(REQUEST_SHIZUKU)
            } catch (e: RuntimeException) {
                // It stopped just now; the banner says so next.
                BackScreen.log("Couldn't ask Shizuku: ${e.message}")
            }
            ShizukuState.BLOCKED -> MaterialAlertDialogBuilder(this)
                .setTitle(R.string.rear_shizuku_banner_blocked)
                .setMessage(R.string.rear_shizuku_banner_blocked_text)
                .setNegativeButton(R.string.rear_cancel, null)
                .setPositiveButton(R.string.rear_open_shizuku) { _, _ -> openShizuku() }
                .show()
            else -> refresh()
        }
    }

    /** Shizuku's app: to start it, or allow the app under Authorized apps. */
    private fun openShizuku() {
        val intent = ShizukuState.openIntent(this) ?: return run { snackbar(R.string.rear_shizuku_cant_open) }
        startActivity(intent)
    }

    /** Shizuku's download page, in the browser. */
    private fun getShizuku() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ShizukuState.DOWNLOAD_URL)))
        } catch (e: ActivityNotFoundException) {
            BackScreen.log("No browser to open Shizuku's download page")
            snackbar(R.string.rear_no_browser)
        }
    }

    private fun showSetupHelp() = SetupHelpDialog.show(supportFragmentManager)

    /** Something needs Shizuku, which isn't ready: says so, with Setup help to sort it out. */
    fun needShizuku() {
        snackbar(R.string.rear_need_shizuku, Snackbar.LENGTH_LONG, R.string.rear_setup_help, ::showSetupHelp)
    }

    /** Reloads everything and puts the wallpaper up afresh, for when something didn't update. */
    private fun refreshBackScreen() {
        (supportFragmentManager.findFragmentByTag(Feature.WALLPAPER.name) as? WallpaperFragment)?.reloadAll()
        when {
            !WallpaperSettings.isEnabled(this) -> snackbar(R.string.rear_refresh_off)
            !RearCommands.isReady() -> needShizuku()
            else -> {
                BackScreen.log("Refreshing")
                KeeperService.setWallpaper(this, true)
                snackbar(R.string.rear_refreshing)
            }
        }
    }

    /** Shows [text] just above the bottom bar, or at the bottom in landscape, with [action] if given. */
    fun snackbar(text: Int, length: Int = Snackbar.LENGTH_SHORT, action: Int? = null, onAction: () -> Unit = {}): Snackbar =
        snackbar(getText(text), length, action, onAction)

    fun snackbar(text: CharSequence, length: Int = Snackbar.LENGTH_SHORT, action: Int? = null, onAction: () -> Unit = {}): Snackbar =
        Snackbar.make(findViewById(R.id.rear_content), text, length)
            .apply { if (nav is BottomNavigationView) anchorView = nav }
            .apply { if (action != null) setAction(action) { onAction() } }
            .also { it.show() }

    companion object {
        private const val REQUEST_SHIZUKU = 2
        private const val REFRESH_MS = 1000L
        private const val BANNER_GRACE_MS = 1500L
        private const val KEY_TAB = "tab"
        private const val KEY_CREATED_AT = "created_at"

        internal fun intent(context: Context, feature: Feature) =
            Intent(context, RearScreenActivity::class.java).putExtra(KEY_TAB, feature.name)
    }
}
