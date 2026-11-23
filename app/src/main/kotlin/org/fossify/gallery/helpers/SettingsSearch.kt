package org.fossify.gallery.helpers

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import java.util.Locale

/**
 * fast25 (owner 11-23-2026: "there should be a search bar at the top of settings page"). Filters the rows of the
 * Settings list as he types: a row matches when every typed word is found in its texts (title and summary or current
 * value); matching rows stay under their section headers, sections without a match and the dividers hide. Nothing runs
 * when Settings opens: the row texts are read on the first keystroke. Every view it hides is remembered with its own
 * visibility, and [clear] puts exactly those back, so rows that Settings itself hides stay hidden. Never throws.
 */
class SettingsSearch(private val holder: ViewGroup, private val isHeader: (View) -> Boolean) {
    private val changed = HashMap<View, Int>()
    var query = ""
        private set

    /** Applies [text]; returns the number of matching rows (or -1 when the query is empty). */
    fun apply(text: String): Int {
        return try {
            restore()
            query = text.trim()
            if (query.isEmpty()) return -1
            val words = query.lowercase(Locale.getDefault()).split(Regex("\\s+")).filter { it.isNotEmpty() }
            var matches = 0
            var header: View? = null
            var headerHasMatch = false
            fun closeSection() {
                header?.let { if (!headerHasMatch) hide(it) }
            }
            for (i in 0 until holder.childCount) {
                val child = holder.getChildAt(i)
                if (isHeader(child)) {
                    closeSection()
                    header = child
                    headerHasMatch = false
                    continue
                }
                if (child.visibility != View.VISIBLE) continue
                val texts = StringBuilder()
                collect(child, texts)
                val hay = texts.toString().lowercase(Locale.getDefault())
                if (hay.isNotBlank() && words.all { hay.contains(it) }) {
                    matches++
                    headerHasMatch = true
                } else {
                    hide(child)   // rows without a match, dividers and empty views
                }
            }
            closeSection()
            matches
        } catch (_: Throwable) {
            restore()
            -1
        }
    }

    /** Shows everything it hid, with the visibility each view had before. */
    fun clear() {
        query = ""
        restore()
    }

    private fun restore() {
        try {
            for ((v, vis) in changed) v.visibility = vis
        } catch (_: Throwable) {
        }
        changed.clear()
    }

    private fun hide(v: View) {
        if (v.visibility == View.GONE) return
        if (!changed.containsKey(v)) changed[v] = v.visibility
        v.visibility = View.GONE
    }

    private fun collect(v: View, out: StringBuilder) {
        if (v.visibility != View.VISIBLE) return
        if (v is TextView) {
            v.text?.let { out.append(it).append(' ') }
            v.hint?.let { out.append(it).append(' ') }
        }
        if (v is ViewGroup) for (i in 0 until v.childCount) collect(v.getChildAt(i), out)
    }
}
