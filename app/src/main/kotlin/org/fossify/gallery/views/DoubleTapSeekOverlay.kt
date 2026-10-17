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
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import org.fossify.gallery.R
import kotlin.math.hypot
import kotlin.math.max

/**
 * fast17: the YouTube style "double tap to seek" feedback. Sits over the video, never takes touches.
 *
 * On [show] it lights up the tapped half with a soft rounded panel, runs a ripple out from the finger, animates three
 * chevrons pointing the seek direction and prints the stacked total ("10 seconds", "20 seconds", ...). Each further tap
 * within the stacking window restarts the ripple and updates the label; the panel fades out once the taps stop.
 */
class DoubleTapSeekOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    companion object {
        /** How long after the last tap the overlay (and the stacking of totals) stays active. */
        const val STACK_WINDOW_MS = 750L
        private const val FADE_MS = 220L
        private const val RIPPLE_MS = 520L
        private const val CHEVRON_CYCLE_MS = 600L
    }

    private var forward = true
    private var seconds = 0
    private var tapX = 0f
    private var tapY = 0f
    private var lastTapAt = 0L
    private var panelAlpha = 0f
    private var rippleProgress = 1f
    private var chevronPhase = 0f
    private var labelText = ""

    private val density = resources.displayMetrics.density
    private val panelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val ripplePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val chevronPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 13f * resources.displayMetrics.scaledDensity
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
        setShadowLayer(3f * density, 0f, 1f * density, 0x66000000)
    }
    private val panelPath = Path()
    private val chevronPath = Path()
    private val clipRect = RectF()

    private val fadeAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = FADE_MS
        interpolator = LinearInterpolator()
        addUpdateListener {
            panelAlpha = it.animatedValue as Float
            invalidate()
        }
    }
    private val rippleAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = RIPPLE_MS
        interpolator = DecelerateInterpolator()
        addUpdateListener {
            rippleProgress = it.animatedValue as Float
            invalidate()
        }
    }
    private val chevronAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = CHEVRON_CYCLE_MS
        interpolator = LinearInterpolator()
        repeatCount = ValueAnimator.INFINITE
        addUpdateListener {
            chevronPhase = it.animatedValue as Float
            invalidate()
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
     * [rawX] and [rawY] are screen coordinates of the tap; [totalSeconds] is the stacked amount to print.
     */
    fun show(forward: Boolean, totalSeconds: Int, rawX: Float, rawY: Float) {
        val location = IntArray(2)
        getLocationOnScreen(location)
        this.forward = forward
        seconds = totalSeconds
        tapX = (rawX - location[0]).coerceIn(0f, max(width.toFloat(), 1f))
        tapY = (rawY - location[1]).coerceIn(0f, max(height.toFloat(), 1f))
        labelText = resources.getQuantityString(R.plurals.seek_seconds, totalSeconds, totalSeconds)
        lastTapAt = SystemClock.uptimeMillis()

        removeCallbacks(hideRunnable)
        if (visibility != VISIBLE || panelAlpha == 0f) {
            visibility = VISIBLE
            fadeAnimator.cancel()
            fadeAnimator.setFloatValues(panelAlpha, 1f)
            fadeAnimator.start()
            chevronAnimator.start()
        } else if (fadeAnimator.isRunning && panelAlpha < 1f && fadeAnimator.animatedFraction > 0f) {
            // a tap while fading out brings it straight back
            fadeAnimator.cancel()
            fadeAnimator.setFloatValues(panelAlpha, 1f)
            fadeAnimator.start()
        }
        rippleAnimator.cancel()
        rippleAnimator.start()
        postDelayed(hideRunnable, STACK_WINDOW_MS)
        invalidate()
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
                    rippleAnimator.cancel()
                }
            }
        })
        fadeAnimator.start()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(hideRunnable)
        fadeAnimator.cancel()
        rippleAnimator.cancel()
        chevronAnimator.cancel()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (panelAlpha <= 0f || width == 0 || height == 0) return
        val w = width.toFloat()
        val h = height.toFloat()

        // the lit panel: the tapped half, its inner edge a wide arc like YouTube's
        val panelWidth = w * 0.5f
        val bulge = w * 0.1f
        panelPath.reset()
        if (forward) {
            panelPath.moveTo(w, 0f)
            panelPath.lineTo(w - panelWidth + bulge, 0f)
            panelPath.quadTo(w - panelWidth - bulge, h / 2f, w - panelWidth + bulge, h)
            panelPath.lineTo(w, h)
        } else {
            panelPath.moveTo(0f, 0f)
            panelPath.lineTo(panelWidth - bulge, 0f)
            panelPath.quadTo(panelWidth + bulge, h / 2f, panelWidth - bulge, h)
            panelPath.lineTo(0f, h)
        }
        panelPath.close()

        panelPaint.alpha = (0.14f * 255 * panelAlpha).toInt()
        canvas.drawPath(panelPath, panelPaint)

        // ripple from the finger, clipped to the panel
        if (rippleProgress < 1f) {
            val maxRadius = hypot(panelWidth + bulge, h)
            val radius = maxRadius * rippleProgress
            ripplePaint.alpha = (0.22f * 255 * (1f - rippleProgress) * panelAlpha).toInt()
            canvas.save()
            canvas.clipPath(panelPath)
            canvas.drawCircle(tapX, tapY, radius, ripplePaint)
            canvas.restore()
        }

        // chevrons and label, centred in the lit half
        val centerX = if (forward) w - panelWidth / 2f + bulge / 4f else panelWidth / 2f - bulge / 4f
        val centerY = h / 2f
        val chevronH = 16f * density
        val chevronW = 10f * density
        val gap = 2f * density
        val totalW = 3 * chevronW + 2 * gap
        var x = centerX - totalW / 2f
        val top = centerY - chevronH / 2f - 10f * density
        for (i in 0 until 3) {
            // which chevron is bright walks in the seek direction
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
        canvas.drawText(labelText, centerX, centerY + chevronH / 2f + 14f * density, textPaint)
        clipRect.set(0f, 0f, w, h)
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
