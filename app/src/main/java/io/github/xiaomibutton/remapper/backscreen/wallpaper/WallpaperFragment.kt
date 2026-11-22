package io.github.xiaomibutton.remapper.backscreen.wallpaper

import android.annotation.TargetApi
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Insets
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree
import androidx.activity.result.contract.ActivityResultContracts.PickMultipleVisualMedia
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.Fragment
import io.github.xiaomibutton.remapper.R
import io.github.xiaomibutton.remapper.backscreen.core.BackScreen
import io.github.xiaomibutton.remapper.backscreen.core.KeeperService
import io.github.xiaomibutton.remapper.backscreen.core.RearCommands
import io.github.xiaomibutton.remapper.backscreen.core.Schedule
import io.github.xiaomibutton.remapper.backscreen.core.ScheduleDialog
import io.github.xiaomibutton.remapper.backscreen.core.ScheduleEditor
import io.github.xiaomibutton.remapper.backscreen.core.Schedules
import io.github.xiaomibutton.remapper.backscreen.rear.RearHostActivity
import io.github.xiaomibutton.remapper.backscreen.ui.MainSwitchBar
import io.github.xiaomibutton.remapper.backscreen.ui.Refreshable
import io.github.xiaomibutton.remapper.backscreen.ui.SwitchRow
import io.github.xiaomibutton.remapper.backscreen.ui.addChoiceChips
import io.github.xiaomibutton.remapper.backscreen.ui.needShizuku
import io.github.xiaomibutton.remapper.backscreen.ui.setChipsEnabled
import io.github.xiaomibutton.remapper.backscreen.ui.snackbar
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.chip.ChipGroup
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import com.google.android.material.snackbar.Snackbar
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * The Wallpaper tab: the switch, a preview shaped like the back screen, the images and gallery,
 * the back screen's display ([DisplayCard]), scaling and panning, the clock, keeping clear of the
 * camera, and schedules.
 */
@TargetApi(35)
class WallpaperFragment : Fragment(R.layout.rear_fragment_wallpaper), Refreshable {

    private lateinit var mainSwitch: MainSwitchBar
    private lateinit var previewFrame: RearPreviewLayout
    private lateinit var preview: WallpaperView
    private lateinit var emptyState: View
    private lateinit var emptyCaption: View
    private lateinit var cameraCard: View
    private lateinit var cameraSwitch: MaterialSwitch
    private lateinit var scheduleSummary: TextView
    private lateinit var scheduleList: LinearLayout
    private lateinit var setupCard: View
    private lateinit var scheduleSetupNote: View
    private lateinit var alarmWarning: View
    private lateinit var batteryWarning: View
    private lateinit var displayCard: DisplayCard

    private lateinit var clock: ClockLayer
    private lateinit var galleryCard: View
    private lateinit var gallerySummary: TextView
    private lateinit var chooseButton: Button
    private lateinit var folderButton: Button
    private lateinit var galleryRotation: View
    private lateinit var intervalValue: TextView
    private lateinit var shuffleSwitch: MaterialSwitch
    private lateinit var scalingChips: ChipGroup
    private lateinit var fitCard: View
    private lateinit var scalingPanNote: View
    private lateinit var panSwitch: MaterialSwitch
    private lateinit var panOptions: View
    private lateinit var panSpeeds: ChipGroup
    private lateinit var panImageNote: TextView
    private lateinit var skipShortSwitch: MaterialSwitch
    private lateinit var skipShortOptions: View
    private lateinit var panMinChips: ChipGroup
    private lateinit var clockSwitch: MaterialSwitch
    private lateinit var clockOptions: View
    private lateinit var clockStyles: ChipGroup
    private lateinit var alignX: MaterialButtonToggleGroup
    private lateinit var alignY: MaterialButtonToggleGroup
    private lateinit var clockColorValue: TextView
    private lateinit var clockColorSwatch: View
    private lateinit var clockBgValue: TextView
    private lateinit var clockBgSwatch: View
    private lateinit var clockOpacity: Slider
    private lateinit var clockOpacityValue: TextView

    private var schedules = emptyList<Schedule>()
    private var previewUri: Uri? = null

    // The chosen folder's name, as the gallery card last showed it, for asking before it's replaced.
    private var folderName: String? = null

    // Whether the folder could be opened when the preview was last loaded.
    private var previewAccess = FolderAccess.OK

    // Reading a big folder and decoding an image can take a second or more. They're done here
    // rather than on the main thread, which the back screen shares: a stall there froze the
    // app and stopped the back screen's pan.
    private val loader = Executors.newSingleThreadExecutor()
    private var previewLoads = 0
    private var previewLoading = false

    // Set while controls are updated to match the settings, so their listeners ignore it.
    private var syncingClock = false

    private val ctx: Context get() = requireContext()

    // System photo picker: only the chosen files are shared, no storage permission needed.
    private val pickImages = registerForActivityResult(PickMultipleVisualMedia()) { uris ->
        if (uris.isNotEmpty()) onImagesPicked(uris)
    }

    // System folder picker: access to just that folder, which you can take back in Settings.
    private val pickFolder = registerForActivityResult(OpenDocumentTree()) { uri -> uri?.let(::onFolderPicked) }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        mainSwitch = view.findViewById(R.id.rear_mainSwitch)
        previewFrame = view.findViewById(R.id.rear_previewFrame)
        preview = view.findViewById(R.id.rear_preview)
        emptyState = view.findViewById(R.id.rear_emptyState)
        emptyCaption = view.findViewById(R.id.rear_emptyCaption)
        cameraCard = view.findViewById(R.id.rear_cameraCard)
        cameraSwitch = view.findViewById(R.id.rear_cameraSwitch)
        scheduleSummary = view.findViewById(R.id.rear_scheduleSummary)
        scheduleList = view.findViewById(R.id.rear_scheduleList)
        setupCard = view.findViewById(R.id.rear_setupCard)
        scheduleSetupNote = view.findViewById(R.id.rear_scheduleSetupNote)
        alarmWarning = view.findViewById(R.id.rear_alarmWarning)
        batteryWarning = view.findViewById(R.id.rear_batteryWarning)
        displayCard = DisplayCard(view.findViewById(R.id.rear_displayCard))
        clock = view.findViewById(R.id.rear_clock)
        galleryCard = view.findViewById(R.id.rear_galleryCard)
        gallerySummary = view.findViewById(R.id.rear_gallerySummary)
        chooseButton = view.findViewById(R.id.rear_chooseButton)
        folderButton = view.findViewById(R.id.rear_folderButton)
        galleryRotation = view.findViewById(R.id.rear_galleryRotation)
        intervalValue = view.findViewById(R.id.rear_intervalValue)
        shuffleSwitch = view.findViewById(R.id.rear_shuffleSwitch)
        scalingChips = view.findViewById(R.id.rear_scalingChips)
        fitCard = view.findViewById(R.id.rear_fitCard)
        scalingPanNote = view.findViewById(R.id.rear_scalingPanNote)
        panSwitch = view.findViewById(R.id.rear_panSwitch)
        panOptions = view.findViewById(R.id.rear_panOptions)
        panSpeeds = view.findViewById(R.id.rear_panSpeeds)
        panImageNote = view.findViewById(R.id.rear_panImageNote)
        skipShortSwitch = view.findViewById(R.id.rear_skipShortSwitch)
        skipShortOptions = view.findViewById(R.id.rear_skipShortOptions)
        panMinChips = view.findViewById(R.id.rear_panMinChips)
        clockSwitch = view.findViewById(R.id.rear_clockSwitch)
        clockOptions = view.findViewById(R.id.rear_clockOptions)
        clockStyles = view.findViewById(R.id.rear_clockStyles)
        alignX = view.findViewById(R.id.rear_alignX)
        alignY = view.findViewById(R.id.rear_alignY)
        clockColorValue = view.findViewById(R.id.rear_clockColorValue)
        clockColorSwatch = view.findViewById(R.id.rear_clockColorSwatch)
        clockBgValue = view.findViewById(R.id.rear_clockBgValue)
        clockBgSwatch = view.findViewById(R.id.rear_clockBgSwatch)
        clockOpacity = view.findViewById(R.id.rear_clockOpacity)
        clockOpacityValue = view.findViewById(R.id.rear_clockOpacityValue)
        // What the preview will show, until it's loaded: no flash of "No wallpaper".
        previewUri = Gallery.shown(ctx)

        mainSwitch.onCheckedChange = { checked ->
            if (checked) turnOn() else turnOff()
            refresh()
        }

        val choose = View.OnClickListener { chooseImages() }
        // At once, rather than once the gallery card has been read.
        chooseButton.setText(if (Gallery.copyCount(ctx) > 0) R.string.rear_add_images else R.string.rear_choose_images)
        chooseButton.setOnClickListener(choose)
        view.findViewById<View>(R.id.rear_previewCard).setOnClickListener(choose)
        view.findViewById<View>(R.id.rear_removeButton).setOnClickListener { confirmRemoveImages() }
        folderButton.setOnClickListener { chooseFolder() }

        view.findViewById<View>(R.id.rear_intervalRow).setOnClickListener { chooseInterval() }
        SwitchRow.bind(view.findViewById(R.id.rear_shuffleRow), shuffleSwitch)
        shuffleSwitch.isChecked = Gallery.shuffle(ctx)
        shuffleSwitch.setOnCheckedChangeListener { _, checked -> Gallery.setShuffle(ctx, checked) }
        view.findViewById<View>(R.id.rear_nextButton).setOnClickListener {
            val app = ctx.applicationContext
            Gallery.io.execute {
                Gallery.skip(app)
                BackScreen.mainHandler.post {
                    RearHostActivity.reload()
                    // The tab's view, not this listener's: the tab may have gone since (turned, say).
                    if (this@WallpaperFragment.view != null) loadPreview()
                }
            }
        }

        scalingChips.addChoiceChips(Scaling.entries, Gallery.scaling(ctx), { getString(it.label) }) { scaling ->
            Gallery.setScaling(ctx, scaling)
            loadPreview()
            RearHostActivity.reload()
        }
        setUpPanning(view)

        setUpClock(view)
        SwitchRow.bind(view.findViewById(R.id.rear_cameraRow), cameraSwitch)
        cameraSwitch.isChecked = WallpaperSettings.avoidCamera(ctx)
        cameraSwitch.setOnCheckedChangeListener { _, checked ->
            WallpaperSettings.setAvoidCamera(ctx, checked)
            showRearShape()
            // Beside the camera the screen is a different shape, so images may pan differently.
            showPanning()
            if (WallpaperSettings.isEnabled(ctx)) KeeperService.start(ctx, KeeperService.ACTION_APPLY)
        }

        view.findViewById<View>(R.id.rear_addScheduleButton).setOnClickListener { editSchedule(null) }
        // The editor's answer, after a rotation too.
        childFragmentManager.setFragmentResultListener(ScheduleDialog.RESULT, viewLifecycleOwner) { _, result ->
            onScheduleEdited(result)
        }
        view.findViewById<View>(R.id.rear_showSetupButton).setOnClickListener { showSetup() }
        view.findViewById<View>(R.id.rear_alarmButton).setOnClickListener {
            startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${ctx.packageName}")))
        }
        view.findViewById<View>(R.id.rear_batteryButton).setOnClickListener {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")))
        }
        schedules = Schedules.load(ctx)
        showSchedules()

        showRearShape()
        loadPreview()
    }

    override fun onResume() {
        super.onResume()
        preview.moving = !isHidden
        displayCard.reload()
        // A chosen folder may have changed while we were away.
        showGallery()
        refresh()
    }

    override fun onPause() {
        super.onPause()
        preview.moving = false
    }

    /** Another tab is showing, or this one again: the preview only pans and plays while it can be seen. */
    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        preview.moving = !hidden && isResumed
    }

    override fun onDestroy() {
        loader.shutdownNow()
        super.onDestroy()
    }

    override fun refresh() {
        if (view == null) return
        val enabled = WallpaperSettings.isEnabled(ctx)
        mainSwitch.isChecked = enabled
        mainSwitch.setSummary(
            when {
                !enabled -> R.string.rear_state_off_xiaomi
                KeeperService.instance?.isWaitingForShizuku == true -> R.string.rear_state_waiting_shizuku
                Gallery.access(ctx) != FolderAccess.OK -> R.string.rear_state_on_folder_lost
                previewUri == null -> R.string.rear_state_on_black
                else -> R.string.rear_state_on
            }
        )
        // Keep up with the gallery moving on while the app is open, and with the folder becoming
        // one it can't open (black, as on the back screen) or can again.
        if (!previewLoading && (previewUri != null || previewAccess != FolderAccess.OK) &&
            (Gallery.shown(ctx) != previewUri || Gallery.access(ctx) != previewAccess)
        ) {
            loadPreview()
        }
        refreshSchedule(enabled)
        refreshSetup()
        displayCard.refresh()
    }

    /** Everything again from the settings, for Refresh back screen. */
    fun reloadAll() {
        if (view == null) return
        showGallery()
        loadPreview()
        showClock()
    }

    /** The next scheduled change. */
    private fun refreshSchedule(enabled: Boolean) {
        val active = schedules.any { it.enabled }
        val next = Schedules.nextChange(schedules, System.currentTimeMillis(), enabled)
        scheduleSummary.text = when {
            !active -> getString(R.string.rear_schedule_none)
            next == null -> getString(R.string.rear_schedule_no_change)
            else -> getString(
                if (next.second) R.string.rear_schedule_turns_on else R.string.rear_schedule_turns_off,
                ScheduleEditor.formatWhen(ctx, next.first)
            )
        }
    }

    /**
     * What has to be allowed for schedules and the clock to run on time: exact alarms, and
     * HyperOS not freezing the app in the background, which holds its alarms back.
     */
    private fun refreshSetup() {
        val needed = schedules.any { it.enabled } || WallpaperSettings.showClock(ctx)
        val exact = ctx.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
        val unrestricted = ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)
        alarmWarning.visibility = if (needed && !exact) View.VISIBLE else View.GONE
        batteryWarning.visibility = if (needed && !unrestricted) View.VISIBLE else View.GONE
        setupCard.visibility = if (needed && !(exact && unrestricted)) View.VISIBLE else View.GONE
        // The card is at the top, far from the schedules it's mostly for.
        val schedulesOn = schedules.any { it.enabled }
        scheduleSetupNote.visibility = if (schedulesOn && !(exact && unrestricted)) View.VISIBLE else View.GONE
    }

    /**
     * Scrolls up to the Setup needed card, and TalkBack goes on from its title: you asked to go
     * there, and the button you tapped has scrolled out of sight.
     */
    @SuppressLint("AccessibilityFocus")
    private fun showSetup() {
        val margin = (8 * resources.displayMetrics.density).toInt()
        requireView().findViewById<NestedScrollView>(R.id.rear_scroll).smoothScrollTo(0, (setupCard.top - margin).coerceAtLeast(0))
        setupCard.findViewById<View>(R.id.rear_setupTitle).performAccessibilityAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null)
    }

    private fun showSchedules() {
        scheduleList.removeAllViews()
        for (schedule in schedules.sortedBy { it.start }) {
            val row = layoutInflater.inflate(R.layout.rear_item_schedule, scheduleList, false)
            val times = getString(
                R.string.rear_schedule_times,
                ScheduleEditor.formatTime(ctx, schedule.start),
                ScheduleEditor.formatTime(ctx, schedule.end)
            )
            val days = ScheduleEditor.formatDays(ctx, schedule.days)
            row.findViewById<TextView>(R.id.rear_scheduleTimes).text = times
            row.findViewById<TextView>(R.id.rear_scheduleDays).text = days
            val switch = row.findViewById<MaterialSwitch>(R.id.rear_scheduleSwitch)
            // The row opens the schedule and the switch turns it on or off, so the switch needs a name of its own.
            switch.contentDescription = getString(R.string.rear_schedule_switch_description, times, days)
            switch.isChecked = schedule.enabled
            switch.setOnCheckedChangeListener { _, checked ->
                saveSchedules(schedules.map { if (it.id == schedule.id) it.copy(enabled = checked) else it })
            }
            row.setOnClickListener { editSchedule(schedule) }
            scheduleList.addView(row)
        }
        scheduleList.visibility = if (schedules.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun editSchedule(schedule: Schedule?) = ScheduleDialog.show(childFragmentManager, schedule)

    /** The editor saved a schedule, or deleted one, which Undo puts back. */
    private fun onScheduleEdited(result: Bundle) {
        val schedule = ScheduleDialog.schedule(result)
        val others = schedules.filter { it.id != schedule.id }
        if (!ScheduleDialog.deleted(result)) {
            saveSchedules(others + schedule)
            showSchedules()
            return
        }
        saveSchedules(others)
        showSchedules()
        BackScreen.log("Schedule deleted")
        val app = ctx.applicationContext
        snackbar(R.string.rear_schedule_deleted, Snackbar.LENGTH_LONG, R.string.rear_undo) { undoDelete(app, schedule) }
    }

    /** Puts back [schedule], just deleted, unless it's back already. The tab may have been turned since. */
    private fun undoDelete(app: Context, schedule: Schedule) {
        val now = Schedules.load(app)
        if (now.none { it.id == schedule.id }) Schedules.save(app, now + schedule)
        BackScreen.log("Schedule put back")
        if (view == null) return
        schedules = Schedules.load(app)
        showSchedules()
        refresh()
    }

    private fun saveSchedules(list: List<Schedule>) {
        schedules = list
        Schedules.save(ctx, list)
        refresh()
    }

    /** With no images the back screen is black, with the clock if that's on. */
    private fun turnOn() {
        if (!RearCommands.isReady()) {
            needShizuku()
            return // the next refresh flips the switch back off
        }
        KeeperService.setWallpaper(ctx, true)
    }

    private fun turnOff() {
        KeeperService.setWallpaper(ctx, false)
    }

    // Changes to the images go through Gallery.io, so one asked for just before the tab is turned
    // or closed still happens. The back screen hears of it either way; the tab, if it's still here.

    /**
     * The photo picker. Its images are added to the app's copies; while a folder is chosen they
     * take its place instead, so that asks first.
     */
    private fun chooseImages() = afterChanges { _, hasFolder ->
        val pick = { pickImages.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly)) }
        if (!hasFolder) return@afterChanges pick()
        MaterialAlertDialogBuilder(ctx)
            .setTitle(folderName?.let { getString(R.string.rear_stop_folder_title, it) } ?: getString(R.string.rear_stop_folder_title_unnamed))
            .setMessage(R.string.rear_stop_folder_message)
            .setNegativeButton(R.string.rear_cancel, null)
            .setPositiveButton(R.string.rear_choose_images) { _, _ -> pick() }
            .show()
    }

    /**
     * The folder picker, opening where the chosen folder is, so it's quick to choose again. A
     * folder removes the app's copies, so with some that asks first.
     */
    private fun chooseFolder() = afterChanges { copies, _ ->
        val pick = { pickFolder.launch(Gallery.folder(ctx)) }
        if (copies == 0) return@afterChanges pick()
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.rear_use_folder_title)
            .setMessage(resources.getQuantityString(R.plurals.rear_use_folder_message, copies, copies))
            .setNegativeButton(R.string.rear_cancel, null)
            .setPositiveButton(R.string.rear_choose_a_folder) { _, _ -> pick() }
            .show()
    }

    /**
     * [then], with how many copies there are and whether a folder is chosen, once any change to
     * the images asked for before has been made: an add still copying would otherwise be missed.
     */
    private fun afterChanges(then: (copies: Int, hasFolder: Boolean) -> Unit) {
        val app = ctx.applicationContext
        Gallery.io.execute {
            val copies = Gallery.copyCount(app)
            val hasFolder = Gallery.folder(app) != null
            BackScreen.mainHandler.post { if (view != null) then(copies, hasFolder) }
        }
    }

    private fun onImagesPicked(uris: List<Uri>) {
        val app = ctx.applicationContext
        val copying = snackbar(R.string.rear_copying_images, Snackbar.LENGTH_INDEFINITE)
        Gallery.io.execute {
            // Instead of a folder they're a new gallery, from the first; otherwise added to it.
            val replacing = Gallery.folder(app) != null
            val error = try {
                Gallery.addImages(app, uris)
                BackScreen.log(if (replacing) "Chose ${uris.size} image(s) instead of the folder" else "Added ${uris.size} image(s)")
                null
            } catch (e: Exception) {
                BackScreen.log("Couldn't copy images: ${e.message}")
                e
            }
            BackScreen.mainHandler.post {
                copying.dismiss()
                // The back screen whether or not this tab is still there: it may have been turned
                // or left while copying. The image showing stays, unless the folder's went.
                if (error == null) showOnBackScreen(app, force = replacing)
                if (view == null) return@post
                when {
                    error != null -> snackbar(R.string.rear_copy_failed)
                    !replacing -> snackbar(resources.getQuantityString(R.plurals.rear_images_added, uris.size, uris.size))
                }
                // Not reloaded if it's the same image, so its pan carries on; refresh() sees a new one.
                if (replacing || previewUri == null) loadPreview()
                showGallery()
            }
        }
    }

    private fun onFolderPicked(folder: Uri) {
        val app = ctx.applicationContext
        Gallery.io.execute {
            val used = try {
                Gallery.setFolder(app, folder)
                true
            } catch (e: Exception) {
                BackScreen.log("Couldn't use folder: ${e.message}")
                false
            }
            BackScreen.mainHandler.post {
                if (used) showOnBackScreen(app)
                if (view == null) return@post
                if (!used) {
                    snackbar(R.string.rear_folder_failed, Snackbar.LENGTH_LONG)
                    return@post
                }
                loadPreview()
                showGallery(folderChosen = true)
            }
        }
    }

    private fun confirmRemoveImages() {
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.rear_remove_images_title)
            .setMessage(R.string.rear_remove_images_message)
            .setNegativeButton(R.string.rear_cancel, null)
            .setPositiveButton(R.string.rear_remove_images) { _, _ ->
                val app = ctx.applicationContext
                Gallery.io.execute {
                    Gallery.clear(app)
                    BackScreen.log("Removed images")
                    BackScreen.mainHandler.post {
                        // The back screen fades to black.
                        showOnBackScreen(app)
                        if (view == null) return@post
                        snackbar(R.string.rear_images_removed)
                        loadPreview()
                        showGallery()
                    }
                }
            }
            .show()
    }

    /**
     * The gallery card: there once there are images; its timing only with more than one to go
     * through, or a folder. It says if the folder can't be opened any more. With [folderChosen],
     * logs the new folder and says if it has no images.
     */
    private fun showGallery(folderChosen: Boolean = false) {
        val app = ctx.applicationContext
        loader.execute {
            // Read afresh: the folder may have changed while the app was away.
            val count = Gallery.images(app, fresh = true).size
            val folder = Gallery.folderName(app)
            val access = Gallery.access(app)
            if (folderChosen) BackScreen.log("Chose folder $folder: $count image(s)")
            BackScreen.mainHandler.post {
                if (view == null) return@post
                showGallery(count, folder, access)
                if (folderChosen && count == 0 && access == FolderAccess.OK) snackbar(R.string.rear_folder_empty, Snackbar.LENGTH_LONG)
            }
        }
    }

    private fun showGallery(count: Int, folder: String?, access: FolderAccess) {
        val hasFolder = Gallery.folder(ctx) != null
        folderName = folder
        // With images of its own, more are added to them.
        chooseButton.setText(if (!hasFolder && count > 0) R.string.rear_add_images else R.string.rear_choose_images)
        galleryCard.visibility = if (count > 0 || hasFolder) View.VISIBLE else View.GONE
        fitCard.visibility = if (count > 0) View.VISIBLE else View.GONE
        galleryRotation.visibility = if (count > 1 || hasFolder) View.VISIBLE else View.GONE
        val images = resources.getQuantityString(R.plurals.rear_gallery_count, count, count)
        gallerySummary.text = when {
            access != FolderAccess.OK ->
                folder?.let { getString(R.string.rear_folder_lost, it) } ?: getString(R.string.rear_folder_lost_unnamed)
            folder == null -> images
            else -> getString(R.string.rear_gallery_from_folder, images, folder)
        }
        folderButton.setText(if (access == FolderAccess.OK) R.string.rear_choose_folder else R.string.rear_choose_folder_again)
        intervalValue.text = formatInterval(Gallery.interval(ctx))
        // The switch's summary says so too.
        refresh()
    }

    private fun chooseInterval() {
        val options = Gallery.INTERVALS
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.rear_change_every)
            .setSingleChoiceItems(
                options.map(::formatInterval).toTypedArray(), options.indexOf(Gallery.interval(ctx))
            ) { dialog, which ->
                Gallery.setInterval(ctx, options[which])
                intervalValue.text = formatInterval(options[which])
                RearHostActivity.settingsChanged()
                dialog.dismiss()
            }
            .show()
    }

    private fun formatInterval(minutes: Int): String = when {
        minutes >= 1440 -> getString(R.string.rear_every_day_interval)
        minutes >= 60 -> resources.getQuantityString(R.plurals.rear_every_hours, minutes / 60, minutes / 60)
        else -> resources.getQuantityString(R.plurals.rear_every_minutes, minutes, minutes)
    }

    private fun setUpClock(view: View) {
        SwitchRow.bind(view.findViewById(R.id.rear_clockRow), clockSwitch)
        clockSwitch.isChecked = WallpaperSettings.showClock(ctx)
        clockSwitch.setOnCheckedChangeListener { _, checked ->
            WallpaperSettings.setShowClock(ctx, checked)
            clockChanged()
        }
        clockStyles.addChoiceChips(ClockStyle.entries, WallpaperSettings.clockStyle(ctx), { getString(it.label) }) { style ->
            WallpaperSettings.setClockStyle(ctx, style)
            clockChanged()
        }

        // Position: buttons for the edges and middle, or drag it in the preview.
        val xs = mapOf(R.id.rear_alignLeft to 0f, R.id.rear_alignCentre to 0.5f, R.id.rear_alignRight to 1f)
        val ys = mapOf(R.id.rear_alignTop to 0f, R.id.rear_alignMiddle to 0.5f, R.id.rear_alignBottom to 1f)
        alignX.addOnButtonCheckedListener { _, id, checked ->
            if (!checked || syncingClock) return@addOnButtonCheckedListener
            WallpaperSettings.setClockPosition(ctx, xs.getValue(id), clock.settings.y)
            clockChanged()
        }
        alignY.addOnButtonCheckedListener { _, id, checked ->
            if (!checked || syncingClock) return@addOnButtonCheckedListener
            WallpaperSettings.setClockPosition(ctx, clock.settings.x, ys.getValue(id))
            clockChanged()
        }
        clock.onMoved = { x, y ->
            WallpaperSettings.setClockPosition(ctx, x, y)
            clockChanged()
        }

        // Colours: Auto reads the image under the clock in the preview, as the back screen does.
        clock.backdrop = preview
        preview.onPanned = clock::backdropMoved
        clock.onColorsChanged = { showClockColors() }
        view.findViewById<View>(R.id.rear_clockColorRow).setOnClickListener {
            ColorPicker.show(
                ctx, R.string.rear_clock_colour, R.string.rear_colour_auto_text, clock.settings.color, clock.autoTextColor
            ) { color ->
                WallpaperSettings.setClockColor(ctx, color)
                clockChanged()
            }
        }
        view.findViewById<View>(R.id.rear_clockBgRow).setOnClickListener {
            ColorPicker.show(
                ctx, R.string.rear_clock_background, R.string.rear_colour_auto_bg, clock.settings.bgColor, clock.autoBgColor
            ) { color ->
                WallpaperSettings.setClockBgColor(ctx, color)
                // A background colour can't be seen with none of it showing.
                if (clock.settings.bgOpacity == 0) WallpaperSettings.setClockBgOpacity(ctx, DEFAULT_BG_OPACITY)
                clockChanged()
            }
        }
        clockOpacity.setLabelFormatter { getString(R.string.rear_percent, it.toInt()) }
        clockOpacity.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            WallpaperSettings.setClockBgOpacity(ctx, value.toInt())
            clockChanged()
        }
        showClock()
    }

    /** A clock setting changed: show it here and on the back screen. */
    private fun clockChanged() {
        showClock()
        RearHostActivity.settingsChanged()
    }

    /** Matches the preview's clock and the clock controls to the settings. */
    private fun showClock() {
        val show = WallpaperSettings.showClock(ctx)
        val settings = WallpaperSettings.clockSettings(ctx)
        clock.visibility = if (show) View.VISIBLE else View.GONE
        clockOptions.visibility = if (show) View.VISIBLE else View.GONE
        clock.settings = settings
        showEmptyState()

        syncingClock = true
        // Only a button that matches exactly; after a drag, maybe none.
        check(alignX, when (settings.x) { 0f -> R.id.rear_alignLeft; 0.5f -> R.id.rear_alignCentre; 1f -> R.id.rear_alignRight; else -> null })
        check(alignY, when (settings.y) { 0f -> R.id.rear_alignTop; 0.5f -> R.id.rear_alignMiddle; 1f -> R.id.rear_alignBottom; else -> null })
        clockOpacity.value = settings.bgOpacity.toFloat().coerceIn(clockOpacity.valueFrom, clockOpacity.valueTo)
        syncingClock = false
        showClockColors()
    }

    private fun check(group: MaterialButtonToggleGroup, id: Int?) {
        if (id == null) group.clearChecked() else group.check(id)
    }

    /** The colour rows, with what Auto picked for the image showing. */
    private fun showClockColors() {
        val settings = clock.settings
        clockColorValue.text = settings.color?.let(::formatColor) ?: getString(R.string.rear_colour_auto)
        clockBgValue.text = settings.bgColor?.let(::formatColor) ?: getString(R.string.rear_colour_auto)
        swatch(clockColorSwatch, clock.textColor)
        swatch(clockBgSwatch, clock.bgColor)
        clockOpacityValue.text = getString(R.string.rear_percent, settings.bgOpacity)
    }

    private fun formatColor(color: Int) = "#%06X".format(Locale.US, color and 0xFFFFFF)

    private fun swatch(view: View, color: Int) {
        view.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            setStroke(
                (resources.displayMetrics.density).toInt().coerceAtLeast(1),
                MaterialColors.getColor(view, com.google.android.material.R.attr.colorOutline)
            )
        }
    }

    /** Matches the preview to the rear display's size and camera cutout. */
    private fun showRearShape() {
        val rear = BackScreen.findRearDisplay(ctx) ?: return
        previewFrame.setRearDisplay(rear, WallpaperSettings.avoidCamera(ctx))
        cameraCard.visibility = if (previewFrame.hasCamera) View.VISIBLE else View.GONE
    }

    /** Loads the gallery's current image into the preview, decoded just as the back screen does. */
    private fun loadPreview() {
        val display = BackScreen.findRearDisplay(ctx) ?: requireActivity().display ?: return
        val app = ctx.applicationContext
        val load = ++previewLoads
        previewLoading = true
        loader.execute {
            val loaded = RearImage.load(app, display, "Preview failed")
            val uri = if (loaded != null) loaded.shown.image else Gallery.shown(app)
            // As the listing just found it.
            val access = Gallery.access(app)
            BackScreen.mainHandler.post {
                if (view == null || load != previewLoads) return@post
                previewLoading = false
                previewAccess = access
                showPreview(uri, loaded?.drawable, loaded?.scaling ?: WallpaperSettings.scalingInUse(app))
            }
        }
    }

    private fun showPreview(uri: Uri?, drawable: Drawable?, scaling: Scaling) {
        previewUri = uri
        // Black like the rear display, wherever the image doesn't reach, or with no image.
        preview.setBackgroundColor(Color.BLACK)
        preview.applySettings(scaling)
        preview.setImageDrawable(drawable)
        // The clock's automatic colours follow the image.
        clock.backdropChanged()
        showEmptyState()
        showPanning()
        // "On" or "On · black, no images".
        refresh()
    }

    private fun setUpPanning(view: View) {
        SwitchRow.bind(view.findViewById(R.id.rear_panRow), panSwitch)
        panSwitch.isChecked = WallpaperSettings.pan(ctx) != null
        panSwitch.setOnCheckedChangeListener { _, checked ->
            WallpaperSettings.setPan(ctx, checked)
            // The options at once; the image may need decoding again, since panning always fills the screen.
            showPanning()
            loadPreview()
            RearHostActivity.reload()
        }
        panSpeeds.addChoiceChips(PanSpeed.entries, WallpaperSettings.panSpeed(ctx), { getString(it.label) }) { speed ->
            WallpaperSettings.setPanSpeed(ctx, speed)
            // The preview and the back screen carry on from where they are, at the new speed.
            preview.pan = speed
            showPanning()
            RearHostActivity.settingsChanged()
        }
        SwitchRow.bind(view.findViewById(R.id.rear_skipShortRow), skipShortSwitch)
        skipShortSwitch.isChecked = WallpaperSettings.skipShortPans(ctx)
        skipShortSwitch.setOnCheckedChangeListener { _, checked ->
            WallpaperSettings.setSkipShortPans(ctx, checked)
            BackScreen.log(if (checked) "Skip short pans on" else "Skip short pans off")
            minTravelChanged()
        }
        panMinChips.addChoiceChips(WallpaperSettings.PAN_MIN_CHOICES, WallpaperSettings.panMinPercent(ctx), { getString(R.string.rear_percent, it) }) { percent ->
            WallpaperSettings.setPanMinPercent(ctx, percent)
            BackScreen.log("Skip pans shorter than $percent%")
            minTravelChanged()
        }
    }

    /** Skip short pans changed: the image showing is planned again, here and on the back screen, without reloading it. */
    private fun minTravelChanged() {
        preview.minTravel = WallpaperSettings.panMinTravel(ctx)
        showPanning()
        RearHostActivity.settingsChanged()
    }

    /** The panning options, the scaling it overrides, and what it does with the image showing. */
    private fun showPanning() {
        val pan = WallpaperSettings.pan(ctx)
        panOptions.visibility = if (pan != null) View.VISIBLE else View.GONE
        skipShortOptions.visibility = if (WallpaperSettings.skipShortPans(ctx)) View.VISIBLE else View.GONE
        scalingPanNote.visibility = if (pan != null) View.VISIBLE else View.GONE
        scalingChips.setChipsEnabled(pan == null)
        val note = pan?.let(::describePan)
        panImageNote.text = note
        panImageNote.visibility = if (note == null) View.GONE else View.VISIBLE
    }

    /**
     * Which way the image in the preview pans on the back screen, and how long each sweep takes,
     * or that it stays still, worked out just as the back screen does: the preview's image is
     * decoded the same way.
     */
    private fun describePan(speed: PanSpeed): String? {
        val image = preview.drawable ?: return null
        val rear = BackScreen.findRearDisplay(ctx) ?: return null
        val screen = BackScreen.naturalSize(rear)
        val inset = if (WallpaperSettings.avoidCamera(ctx)) BackScreen.naturalCameraInsets(rear) else Insets.NONE
        val plan = PanPlanner.plan(
            image.intrinsicWidth, image.intrinsicHeight,
            screen.width - inset.left - inset.right, screen.height - inset.top - inset.bottom,
            screen.height, speed, WallpaperSettings.panMinTravel(ctx),
        )
        val sweep = formatDuration(plan.sweepMs)
        return when {
            plan.skipped -> getString(R.string.rear_pan_skipped, (plan.travel * 100).roundToInt())
            plan.axis == PanAxis.NONE -> getString(R.string.rear_pan_fits)
            plan.axis == PanAxis.HORIZONTAL -> getString(R.string.rear_pan_horizontal, sweep)
            else -> getString(R.string.rear_pan_vertical, sweep)
        }
    }

    private fun formatDuration(ms: Long): String {
        val seconds = ((ms + 500) / 1000).toInt()
        return if (seconds < 60) getString(R.string.rear_duration_seconds, seconds)
        else getString(R.string.rear_duration_minutes, seconds / 60, seconds % 60)
    }

    /**
     * With no images the preview is black like the back screen, and says so. With the clock
     * on, the preview shows just the clock and the note goes underneath, so they don't overlap.
     */
    private fun showEmptyState() {
        val empty = previewUri == null
        val clockOn = WallpaperSettings.showClock(ctx)
        emptyState.visibility = if (empty && !clockOn) View.VISIBLE else View.GONE
        emptyCaption.visibility = if (empty && clockOn) View.VISIBLE else View.GONE
    }

    private companion object {
        const val DEFAULT_BG_OPACITY = 50

        /**
         * The images changed: the back screen shows the gallery's image, or black. Without [force]
         * (images added), the one showing stays. Needs no tab.
         */
        fun showOnBackScreen(app: Context, force: Boolean = true) {
            if (WallpaperSettings.isEnabled(app) && !RearHostActivity.reload(force)) {
                KeeperService.start(app, KeeperService.ACTION_APPLY)
            }
        }
    }
}
