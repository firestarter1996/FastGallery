package org.fossify.gallery.views

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import org.fossify.gallery.R
import kotlin.math.max

/**
 * fast17: the YouTube style "double tap to seek" feedback. Sits over the video, never takes touches.
 *
 * fast18 (owner 10-24-2026: the seek was "slow, jittery" with a "huge white overlay"): the half screen white panel and
 * the ripple are gone. What shows now is one small dark pill in the tapped half (three animated chevrons over the
 * stacked total, "10 seconds", "20 seconds", ...), and only that pill is redrawn on each animation frame, so the
 * video decoder keeps the GPU to itself while it seeks. Each further tap within the stacking window updates the label;
 * the pill fades out once the taps stop.
 */
class DoubleTapSeekOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    companion object {
        /** How long after the last tap the overlay (and the stacking of totals) stays active. */
        const val STACK_WINDOW_MS = 750L
        private const val FADE_MS = 180L
        private const val CHEVRON_CYCLE_MS = 600L
    }

    private var forward = true
    private var seconds = 0
    private var lastTapAt = 0L
    private var panelAlpha = 0f
    private var chevronPhase = 0f
    private var labelText = ""

    private val density = resources.displayMetrics.density
    private val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private val chevronPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 12f * resources.displayMetrics.scaledDensity
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val chevronPath = Path()
    private val pill = RectF()
    private val dirty = android.graphics.Rect()

    private val fadeAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = FADE_MS
        interpolator = LinearInterpolator()
        addUpdateListener {
            panelAlpha = it.animatedValue as Float
            invalidatePill()
        }
    }
    private val chevronAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = CHEVRON_CYCLE_MS
        interpolator = LinearInterpolator()
        repeatCount = ValueAnimator.INFINITE
        addUpdateListener {
            chevronPhase = it.animatedValue as Float
            invalidatePill()
        }
    }
    private val hideRunnable = Runnable { hide() }

    init {
        isClickable = false
        isFocusable = false
        setWillNotDraw(false)
        visibility = GONE
    }

    /** Whether a tap now would stack onto the running total instead of starting a new one. */
    fun isActive(): Boolean = visibility == VISIBLE && SystemClock.uptimeMillis() - lastTapAt <= STACK_WINDOW_MS

    fun currentDirection(): Boolean = forward

    /**
     * [rawX] and [rawY] are screen coordinates of the tap (kept for callers; the pill sits at a fixed spot in the tapped
     * half so it never covers the finger); [totalSeconds] is the stacked amount to print.
     */
    fun show(forward: Boolean, totalSeconds: Int, rawX: Float, rawY: Float) {
        val directionChanged = this.forward != forward && visibility == VISIBLE
        this.forward = forward
        seconds = totalSeconds
        labelText = resources.getQuantityString(R.plurals.seek_seconds, totalSeconds, totalSeconds)
        lastTapAt = SystemClock.uptimeMillis()
        removeCallbacks(hideRunnable)
        if (directionChanged) invalidate()           // the old pill's spot needs clearing once
        if (visibility != VISIBLE || panelAlpha == 0f) {
            visibility = VISIBLE
            fadeAnimator.cancel()
            fadeAnimator.removeAllListeners()
            fadeAnimator.setFloatValues(panelAlpha, 1f)
            fadeAnimator.start()
            chevronAnimator.start()
        } else if (fadeAnimator.isRunning && panelAlpha < 1f) {
            fadeAnimator.cancel()
            fadeAnimator.removeAllListeners()
            fadeAnimator.setFloatValues(panelAlpha, 1f)
            fadeAnimator.start()
        }
        postDelayed(hideRunnable, STACK_WINDOW_MS)
        invalidatePill()
    }

    fun hide() {
        removeCallbacks(hideRunnable)
        if (visibility != VISIBLE) return
        fadeAnimator.cancel()
        fadeAnimator.setFloatValues(panelAlpha, 0f)
        fadeAnimator.removeAllListeners()
        fadeAnimator.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                fadeAnimator.removeListener(this)
                if (panelAlpha == 0f) {
                    visibility = GONE
                    chevronAnimator.cancel()
                }
            }
        })
        fadeAnimator.start()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(hideRunnable)
        fadeAnimator.cancel()
        chevronAnimator.cancel()
    }

    /** The pill's box for the current direction and label (in view coordinates). */
    private fun pillRect(out: RectF) {
        val w = width.toFloat()
        val h = height.toFloat()
        val textW = textPaint.measureText(labelText)
        val pillW = max(textW, 3 * 8f * density + 2 * 3f * density) + 28f * density
        val pillH = 58f * density
        val centerX = if (forward) w * 0.75f else w * 0.25f
        val centerY = h / 2f
        out.set(centerX - pillW / 2f, centerY - pillH / 2f, centerX + pillW / 2f, centerY + pillH / 2f)
    }

    private fun invalidatePill() {
        if (width == 0 || height == 0) { invalidate(); return }
        pillRect(pill)
        val pad = (4f * density).toInt()
        dirty.set(pill.left.toInt() - pad, pill.top.toInt() - pad, pill.right.toInt() + pad, pill.bottom.toInt() + pad)
        invalidate(dirty)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (panelAlpha <= 0f || width == 0 || height == 0) return
        pillRect(pill)
        val radius = pill.height() / 2f
        pillPaint.alpha = (0.55f * 255 * panelAlpha).toInt()
        canvas.drawRoundRect(pill, radius, radius, pillPaint)

        val centerX = pill.centerX()
        val chevronH = 12f * density
        val chevronW = 8f * density
        val gap = 3f * density
        val totalW = 3 * chevronW + 2 * gap
        var x = centerX - totalW / 2f
        val top = pill.top + 10f * density
        for (i in 0 until 3) {
            val index = if (forward) i else 2 - i
            val phase = ((chevronPhase * 3f) - index).let { p -> ((p % 3f) + 3f) % 3f }
            val bright = (1f - (phase / 3f)).coerceIn(0f, 1f)
            chevronPaint.alpha = ((0.35f + 0.65f * bright) * 255 * panelAlpha).toInt()
            chevronPath.reset()
            if (forward) {
                chevronPath.moveTo(x, top)
                chevronPath.lineTo(x + chevronW, top + chevronH / 2f)
                chevronPath.lineTo(x, top + chevronH)
            } else {
                chevronPath.moveTo(x + chevronW, top)
                chevronPath.lineTo(x, top + chevronH / 2f)
                chevronPath.lineTo(x + chevronW, top + chevronH)
            }
            chevronPath.close()
            canvas.drawPath(chevronPath, chevronPaint)
            x += chevronW + gap
        }
        textPaint.alpha = (255 * panelAlpha).toInt()
        canvas.drawText(labelText, centerX, pill.bottom - 14f * density, textPaint)
    }
}

/**
 * fast17: the seek logic behind [DoubleTapSeekOverlay]. The screen is split into thirds: left third back, right third
 * forward, the middle is left to the caller (play/pause like upstream). Taps within the stacking window add up
 * (10, 20, 30 seconds) and while the overlay is active every single tap on a side keeps seeking, as on YouTube.
 */
class DoubleTapSeeker(
    private val overlay: DoubleTapSeekOverlay,
    private val stepMs: Long,
    private val viewWidth: () -> Int,
    private val seekBy: (deltaMs: Long) -> Unit,
) {
    private var totalSeconds = 0

    enum class Zone { LEFT, CENTER, RIGHT }

    fun zoneOf(rawX: Float): Zone {
        val width = viewWidth()
        if (width <= 0) return Zone.CENTER
        val location = IntArray(2)
        overlay.getLocationOnScreen(location)
        val x = rawX - location[0]
        val third = width / 3f
        return when {
            x < third -> Zone.LEFT
            x > width - third -> Zone.RIGHT
            else -> Zone.CENTER
        }
    }

    fun isActive() = overlay.isActive()

    /** A double tap (or a single tap while active) landed in [zone]. Returns true when it was a seek. */
    fun tap(zone: Zone, rawX: Float, rawY: Float): Boolean {
        val forward = when (zone) {
            Zone.LEFT -> false
            Zone.RIGHT -> true
            Zone.CENTER -> return false
        }
        seek(forward, rawX, rawY)
        return true
    }

    fun seek(forward: Boolean, rawX: Float, rawY: Float) {
        if (!overlay.isActive() || overlay.currentDirection() != forward) {
            totalSeconds = 0
        }
        totalSeconds += (stepMs / 1000L).toInt()
        seekBy(if (forward) stepMs else -stepMs)
        overlay.show(forward, totalSeconds, rawX, rawY)
    }
}
