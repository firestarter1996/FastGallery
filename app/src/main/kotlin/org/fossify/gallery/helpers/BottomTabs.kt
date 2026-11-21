package org.fossify.gallery.helpers

import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.fossify.commons.extensions.getProperBackgroundColor
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.getProperTextColor
import kotlin.math.max

/**
 * fast21: the floating bottom bar of the main screen (Camera, Gallery, Search, Settings).
 * Four static views inflated with the screen: no animation, no scroll listener, no redraw of its own. Every size is
 * clamped and every entry point is wrapped, so the bar can never take the app down.
 */
class BottomTabs(private val bar: LinearLayout, private val grid: RecyclerView) {
    private var selected = 1
    private var extraBottom = 0
    private var baseMargin = -1

    private val spacer = object : RecyclerView.ItemDecoration() {
        override fun getItemOffsets(outRect: android.graphics.Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
            try {
                if (extraBottom <= 0 || bar.visibility != View.VISIBLE) return
                val lm = parent.layoutManager as? GridLayoutManager ?: return
                if (lm.orientation != RecyclerView.VERTICAL) return
                val count = state.itemCount
                val span = max(1, lm.spanCount)
                val pos = parent.getChildAdapterPosition(view)
                if (pos == RecyclerView.NO_POSITION || count <= 0) return
                val lastRowStart = ((count - 1) / span) * span
                if (pos >= lastRowStart) outRect.bottom = extraBottom
            } catch (_: Throwable) {
            }
        }
    }

    fun setup(visible: Boolean, onTab: (Int) -> Unit) {
        try {
            if (!visible) {
                bar.visibility = View.GONE
                return
            }
            for (i in 0 until bar.childCount) {
                bar.getChildAt(i).setOnClickListener { safely { onTab(i) } }
            }
            grid.addItemDecoration(spacer)
            bar.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
                if (bottom - top != oldBottom - oldTop || extraBottom == 0) safely { place() }
            }
            ViewCompat.setOnApplyWindowInsetsListener(bar) { _, insets ->
                safely { place(insets) }
                insets
            }
            applyColors()
        } catch (_: Throwable) {
            bar.visibility = View.GONE
        }
    }

    private fun place(given: WindowInsetsCompat? = null) {
        val lp = bar.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        if (baseMargin < 0) baseMargin = max(0, lp.bottomMargin)
        val insets = given ?: ViewCompat.getRootWindowInsets(bar)
        val nav = max(0, insets?.getInsets(WindowInsetsCompat.Type.systemBars())?.bottom ?: 0)
        val want = baseMargin + nav
        if (lp.bottomMargin != want) {
            lp.bottomMargin = want
            bar.layoutParams = lp
        }
        // the grid already pads for the system bar itself; the last row only has to clear the bar and its gap
        val extra = max(0, bar.height + baseMargin * 2)
        if (extra != extraBottom) {
            extraBottom = extra
            grid.post { safely { grid.invalidateItemDecorations() } }
        }
    }

    fun select(index: Int) {
        if (index == selected) return
        selected = index
        applyColors()
    }

    fun applyColors() {
        safely {
            if (bar.visibility != View.VISIBLE) return@safely
            val ctx = bar.context
            val text = ctx.getProperTextColor()
            val primary = ctx.getProperPrimaryColor()
            val back = ctx.getProperBackgroundColor()
            val radius = 30f * ctx.resources.displayMetrics.density
            bar.background = GradientDrawable().apply {
                cornerRadius = radius
                setColor(blend(back, text, 0.10f))
                setStroke(max(1, ctx.resources.displayMetrics.density.toInt()), withAlpha(text, 0.14f))
            }
            for (i in 0 until bar.childCount) {
                val tab = bar.getChildAt(i) as? ViewGroup ?: continue
                val on = i == selected
                val color = if (on) primary else withAlpha(text, 0.78f)
                tab.background = if (on) GradientDrawable().apply {
                    cornerRadius = radius
                    setColor(withAlpha(primary, 0.16f))
                } else null
                (tab.getChildAt(0) as? ImageView)?.imageTintList = ColorStateList.valueOf(color)
                (tab.getChildAt(1) as? TextView)?.setTextColor(color)
            }
        }
    }

    private fun withAlpha(color: Int, alpha: Float): Int {
        val a = (255 * alpha).toInt().coerceIn(0, 255)
        return (color and 0x00FFFFFF) or (a shl 24)
    }

    private fun blend(a: Int, b: Int, t: Float): Int {
        fun ch(shift: Int): Int {
            val x = (a shr shift) and 0xFF
            val y = (b shr shift) and 0xFF
            return (x + (y - x) * t).toInt().coerceIn(0, 255)
        }
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    private inline fun safely(block: () -> Unit) {
        try {
            block()
        } catch (_: Throwable) {
        }
    }
}
