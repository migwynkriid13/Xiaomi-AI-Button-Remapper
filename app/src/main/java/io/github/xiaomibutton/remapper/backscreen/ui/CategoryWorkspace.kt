package io.github.xiaomibutton.remapper.backscreen.ui

import android.content.Context
import android.graphics.Rect
import android.os.Bundle
import android.os.Parcelable
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import androidx.core.widget.NestedScrollView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import io.github.xiaomibutton.remapper.R

/** A category's task picker, shared canvas and focused editing panels. */
class CategoryWorkspace @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {
    private val previewSections: Set<Int>
    private lateinit var sections: List<View>
    private lateinit var tabs: List<Chip>
    private lateinit var navigation: HorizontalScrollView
    private lateinit var scroll: NestedScrollView
    private lateinit var column: LinearLayout
    private var preview: View? = null
    private var selected = 0
    private var positions = IntArray(0)

    init {
        orientation = VERTICAL
        context.obtainStyledAttributes(attrs, R.styleable.CategoryWorkspace).apply {
            previewSections = getString(R.styleable.CategoryWorkspace_previewSections)
                ?.split(',')?.mapNotNull { it.trim().toIntOrNull() }?.toSet() ?: setOf(0)
            recycle()
        }
    }

    override fun onFinishInflate() {
        super.onFinishInflate()
        val children = (0 until childCount).map(::getChildAt)
        sections = children.filter { it.tag != null }
        positions = IntArray(sections.size)
        preview = children.firstOrNull { it.id == R.id.rear_workspacePreview }
        removeAllViews()

        val choices = ChipGroup(context).apply {
            isSingleSelection = true
            isSelectionRequired = true
            isSingleLine = true
            setPadding(dp(20), dp(4), dp(20), dp(8))
        }
        tabs = sections.mapIndexed { index, section ->
            (inflate(context, R.layout.rear_chip_workspace, null) as Chip).apply {
                id = View.generateViewId()
                text = section.tag.toString()
                isSaveEnabled = false
                setOnClickListener { select(index) }
                choices.addView(this)
            }
        }
        navigation = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(choices)
        }
        addView(navigation, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        column = object : LinearLayout(context) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val width = MeasureSpec.getSize(widthMeasureSpec).coerceAtMost(dp(680))
                super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), heightMeasureSpec)
            }
        }.apply {
            orientation = VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(32))
        }
        children.forEach { child ->
            column.addView(child, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
        val centered = FrameLayout(context).apply {
            addView(column, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        }
        scroll = NestedScrollView(context).apply {
            id = R.id.rear_scroll
            isSaveEnabled = false
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            clipToPadding = false
            addView(centered)
        }
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        select(0, remember = false)
    }

    private fun select(index: Int, remember: Boolean = true) {
        if (remember) positions[selected] = scroll.scrollY
        selected = index.coerceIn(sections.indices)
        sections.forEachIndexed { i, section -> section.visibility = if (i == selected) VISIBLE else GONE }
        preview?.visibility = if (selected in previewSections) VISIBLE else GONE
        tabs.forEachIndexed { i, chip -> chip.isChecked = i == selected }
        scroll.post { scroll.scrollTo(0, positions[selected]) }
        navigation.post { navigation.smoothScrollTo((tabs[selected].left - dp(20)).coerceAtLeast(0), 0) }
    }

    fun openSection(label: String) {
        val index = sections.indexOfFirst { it.tag == label }
        if (index >= 0) select(index)
    }

    /** Open the panel containing a recovery control, then scroll to its actual nested bounds. */
    fun reveal(target: View) {
        val index = sections.indexOfFirst { section ->
            var ancestor: View? = target
            while (ancestor != null && ancestor !== section) ancestor = ancestor.parent as? View
            ancestor === section
        }
        if (index >= 0 && index != selected) select(index)
        scroll.post {
            val bounds = Rect(0, 0, target.width, target.height)
            column.offsetDescendantRectToMyCoords(target, bounds)
            scroll.smoothScrollTo(0, (bounds.top - dp(12)).coerceAtLeast(0))
        }
    }

    override fun onSaveInstanceState(): Parcelable = Bundle().apply {
        positions[selected] = scroll.scrollY
        putParcelable("parent", super.onSaveInstanceState())
        putInt("section", selected)
        putIntArray("positions", positions)
    }

    @Suppress("DEPRECATION")
    override fun onRestoreInstanceState(state: Parcelable?) {
        if (state !is Bundle) return super.onRestoreInstanceState(state)
        super.onRestoreInstanceState(state.getParcelable("parent"))
        state.getIntArray("positions")?.takeIf { it.size == sections.size }?.let { positions = it }
        select(state.getInt("section"), remember = false)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
