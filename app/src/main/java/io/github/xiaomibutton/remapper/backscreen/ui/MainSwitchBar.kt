package io.github.xiaomibutton.remapper.backscreen.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import io.github.xiaomibutton.remapper.R
import com.google.android.material.R as MaterialR
import com.google.android.material.color.MaterialColors
import com.google.android.material.materialswitch.MaterialSwitch

/**
 * A compact activation control inside the editor's introduction. The whole surface is a single
 * accessible switch; the summary reports the live service state.
 */
class MainSwitchBar @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    /** Called when you flip it, not when [isChecked] is set from code. */
    var onCheckedChange: ((Boolean) -> Unit)? = null

    // Settings own this state, so Android cannot restore a stale toggle after a configuration change.
    var isChecked = false
        set(value) {
            if (field == value) return
            field = value
            toggle.isChecked = value
            updateColors()
        }

    private val title: TextView
    private val summary: TextView
    private val toggle: MaterialSwitch
    private val shape = GradientDrawable()

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val dp = resources.displayMetrics.density
        minimumHeight = (76 * dp).toInt()
        setPaddingRelative((18 * dp).toInt(), (14 * dp).toInt(), (14 * dp).toInt(), (14 * dp).toInt())
        LayoutInflater.from(context).inflate(R.layout.rear_view_main_switch_bar, this)
        title = findViewById(R.id.rear_mainSwitchTitle)
        summary = findViewById(R.id.rear_mainSwitchSummary)
        toggle = findViewById(R.id.rear_mainSwitchToggle)
        toggle.isSaveEnabled = false
        // The bar is the switch for screen readers; the toggle inside only shows it.
        toggle.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        context.obtainStyledAttributes(attrs, intArrayOf(android.R.attr.text)).apply {
            title.text = getText(0)
            recycle()
        }

        shape.cornerRadius = 20 * dp
        shape.setStroke(dp.toInt().coerceAtLeast(1), color(MaterialR.attr.colorOutlineVariant))
        val ripple = MaterialColors.getColor(this, androidx.appcompat.R.attr.colorControlHighlight)
        background = RippleDrawable(ColorStateList.valueOf(ripple), shape, null)
        isClickable = true
        isFocusable = true
        setOnClickListener {
            isChecked = !isChecked
            onCheckedChange?.invoke(isChecked)
        }
        updateColors()
    }

    fun setSummary(text: CharSequence?) {
        summary.text = text
        summary.visibility = if (text.isNullOrEmpty()) GONE else VISIBLE
    }

    fun setSummary(text: Int) = setSummary(context.getText(text))

    // The bar takes every touch, so dragging the switch's thumb can't flip it behind our back.
    override fun onInterceptTouchEvent(event: MotionEvent) = true

    override fun getAccessibilityClassName(): CharSequence = Switch::class.java.name

    // setChecked(Boolean) is deprecated in Android 16, but its replacement needs Android 16.
    @Suppress("DEPRECATION")
    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.isCheckable = true
        info.isChecked = isChecked
    }

    private fun updateColors() {
        val on = isChecked
        shape.setColor(color(MaterialR.attr.colorSurfaceContainer))
        title.setTextColor(color(MaterialR.attr.colorOnSurface))
        summary.setTextColor(color(if (on) androidx.appcompat.R.attr.colorPrimary else MaterialR.attr.colorOnSurfaceVariant))
    }

    private fun color(attr: Int) = MaterialColors.getColor(this, attr)

}
