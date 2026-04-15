package com.homeai.assistant.ui

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.LinearInterpolator
import kotlin.math.min
import kotlin.random.Random

/**
 * EyesView – custom view rendering two soft emerald-green neon egg-shaped eyes.
 *
 * Features:
 *  - Vertical oval (egg-shaped) eyes on dark background
 *  - Soft emerald-green neon radial gradient glow
 *  - Pupils inside each eye
 *  - Constant breathing glow (pulsing alpha/size)
 *  - Subtle pupil drift movement
 *  - Occasional blink (vertical squash animation)
 *
 * Optimised for low-end devices: no bitmap allocation per frame, minimal
 * allocations in onDraw, all animations driven by ValueAnimator on the
 * main thread.
 */
class EyesView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // ─────────────────────────────────────────────────────────────
    // Colours
    // ─────────────────────────────────────────────────────────────

    private val emeraldCore   = Color.parseColor("#00FF99")   // bright core
    private val emeraldMid    = Color.parseColor("#00C87A")   // mid glow
    private val emeraldEdge   = Color.parseColor("#003322")   // dark edge glow
    private val pupilColor    = Color.parseColor("#001A0D")   // very dark pupil
    private val glowHalo      = Color.parseColor("#40004422") // subtle outer halo

    // ─────────────────────────────────────────────────────────────
    // Paint objects (allocated once)
    // ─────────────────────────────────────────────────────────────

    private val eyePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        maskFilter = BlurMaskFilter(60f, BlurMaskFilter.Blur.NORMAL)
    }
    private val pupilPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = pupilColor
        style = Paint.Style.FILL
    }
    private val pupilGlintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(120, 255, 255, 255)
        style = Paint.Style.FILL
    }

    // ─────────────────────────────────────────────────────────────
    // Geometry – computed in onSizeChanged
    // ─────────────────────────────────────────────────────────────

    private val leftEyeRect  = RectF()
    private val rightEyeRect = RectF()

    private var eyeRadiusX = 0f   // half-width  of eye oval
    private var eyeRadiusY = 0f   // half-height of eye oval
    private var eyeGapX    = 0f   // distance from centre to each eye centre
    private var eyeCentreY = 0f

    private var leftEyeCX  = 0f
    private var rightEyeCX = 0f

    private var pupilRadius = 0f

    // ─────────────────────────────────────────────────────────────
    // Animation state
    // ─────────────────────────────────────────────────────────────

    /** 0f..1f – drives breathing glow size & alpha */
    private var breathPhase = 0f

    /** 0f..1f – drives vertical squash (0 = open, 1 = blink closed) */
    private var blinkPhase = 0f

    /** Pupil offset in pixels from eye centre */
    private var pupilOffsetX = 0f
    private var pupilOffsetY = 0f

    // Animators
    private val breathAnimator: ValueAnimator by lazy {
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 3200
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { anim ->
                breathPhase = anim.animatedValue as Float
                invalidate()
            }
        }
    }

    private val pupilXAnimator: ValueAnimator by lazy {
        ValueAnimator.ofFloat(-1f, 1f).apply {
            duration = 4000
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { anim ->
                pupilOffsetX = (anim.animatedValue as Float) * eyeRadiusX * 0.25f
                invalidate()
            }
        }
    }

    private val pupilYAnimator: ValueAnimator by lazy {
        ValueAnimator.ofFloat(-1f, 1f).apply {
            duration = 5500
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { anim ->
                pupilOffsetY = (anim.animatedValue as Float) * eyeRadiusY * 0.15f
                invalidate()
            }
        }
    }

    private var blinkAnimator: ValueAnimator? = null
    private var blinkScheduler: Runnable? = null

    // ─────────────────────────────────────────────────────────────
    // View lifecycle
    // ─────────────────────────────────────────────────────────────

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startAnimations()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopAnimations()
    }

    /** Called from MainActivity.onResume() */
    fun resumeAnimations() {
        if (!breathAnimator.isRunning) startAnimations()
    }

    /** Called from MainActivity.onPause() */
    fun pauseAnimations() {
        breathAnimator.pause()
        pupilXAnimator.pause()
        pupilYAnimator.pause()
        removeCallbacks(blinkScheduler)
    }

    private fun startAnimations() {
        breathAnimator.start()
        pupilXAnimator.start()
        pupilYAnimator.start()
        scheduleNextBlink()
    }

    private fun stopAnimations() {
        breathAnimator.cancel()
        pupilXAnimator.cancel()
        pupilYAnimator.cancel()
        blinkAnimator?.cancel()
        removeCallbacks(blinkScheduler)
    }

    // ─────────────────────────────────────────────────────────────
    // Blink scheduling
    // ─────────────────────────────────────────────────────────────

    private fun scheduleNextBlink() {
        blinkScheduler?.let { removeCallbacks(it) }
        // Random interval between 2 and 7 seconds
        val delay = (2000L + Random.nextLong(5000L))
        blinkScheduler = Runnable { doBlink() }
        postDelayed(blinkScheduler, delay)
    }

    private fun doBlink() {
        blinkAnimator?.cancel()
        blinkAnimator = ValueAnimator.ofFloat(0f, 1f, 0f).apply {
            duration = 180
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { anim ->
                blinkPhase = anim.animatedValue as Float
                invalidate()
            }
            // After blink ends, schedule the next one
            doOnEnd { scheduleNextBlink() }
        }
        blinkAnimator?.start()
    }

    // ─────────────────────────────────────────────────────────────
    // Layout
    // ─────────────────────────────────────────────────────────────

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        computeGeometry(w.toFloat(), h.toFloat())
    }

    private fun computeGeometry(w: Float, h: Float) {
        // Eyes occupy roughly the middle third of the view
        eyeRadiusX = min(w, h) * 0.13f
        eyeRadiusY = eyeRadiusX * 1.45f   // taller than wide → egg-shaped
        eyeGapX    = w * 0.22f
        eyeCentreY = h * 0.50f

        leftEyeCX  = w / 2f - eyeGapX
        rightEyeCX = w / 2f + eyeGapX

        pupilRadius = eyeRadiusX * 0.38f

        updateEyeRects(1f)
    }

    private fun updateEyeRects(scaleY: Float) {
        val ry = eyeRadiusY * scaleY
        leftEyeRect.set(
            leftEyeCX  - eyeRadiusX, eyeCentreY - ry,
            leftEyeCX  + eyeRadiusX, eyeCentreY + ry
        )
        rightEyeRect.set(
            rightEyeCX - eyeRadiusX, eyeCentreY - ry,
            rightEyeCX + eyeRadiusX, eyeCentreY + ry
        )
    }

    // ─────────────────────────────────────────────────────────────
    // Drawing
    // ─────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (eyeRadiusX == 0f) return

        // Breathing: scale glow slightly and modulate alpha
        val breathScale = 1f + breathPhase * 0.06f          // 1.0 → 1.06
        val glowAlpha   = (180 + (breathPhase * 70).toInt()).coerceIn(0, 255)

        // Blink: vertical squash
        val eyeScaleY = 1f - blinkPhase * 0.95f             // fully closed ≈ 0.05

        updateEyeRects(eyeScaleY)

        // ── Draw each eye ──────────────────────────────────────
        drawEye(canvas, leftEyeCX,  eyeCentreY, breathScale, glowAlpha, eyeScaleY)
        drawEye(canvas, rightEyeCX, eyeCentreY, breathScale, glowAlpha, eyeScaleY)
    }

    private fun drawEye(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        breathScale: Float,
        glowAlpha: Int,
        eyeScaleY: Float
    ) {
        val ry = eyeRadiusY * eyeScaleY
        val eyeRect = RectF(cx - eyeRadiusX, cy - ry, cx + eyeRadiusX, cy + ry)

        // ── Outer diffuse glow (blurred halo) ──────────────────
        val haloRx = eyeRadiusX * 1.8f * breathScale
        val haloRy = ry * 1.8f * breathScale
        glowPaint.color = Color.argb(
            (glowAlpha * 0.55f).toInt(),
            Color.red(emeraldEdge),
            Color.green(emeraldEdge),
            Color.blue(emeraldEdge)
        )
        val haloRect = RectF(cx - haloRx, cy - haloRy, cx + haloRx, cy + haloRy)
        canvas.drawOval(haloRect, glowPaint)

        // ── Eye fill with radial gradient ──────────────────────
        val gradient = RadialGradient(
            cx, cy,
            maxOf(eyeRadiusX, ry),
            intArrayOf(emeraldCore, emeraldMid, emeraldEdge),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
        eyePaint.shader = gradient
        eyePaint.alpha = glowAlpha
        canvas.drawOval(eyeRect, eyePaint)

        // ── Inner bright streak (neon highlight) ───────────────
        val streakPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(
                (glowAlpha * 0.45f).toInt(),
                255, 255, 255
            )
            style = Paint.Style.FILL
        }
        val streakRx = eyeRadiusX * 0.35f * breathScale
        val streakRy = ry * 0.18f * breathScale
        val streakRect = RectF(
            cx - streakRx, cy - ry * 0.55f,
            cx + streakRx, cy - ry * 0.55f + streakRy * 2
        )
        canvas.drawOval(streakRect, streakPaint)

        // ── Pupil (only when eye is reasonably open) ───────────
        if (eyeScaleY > 0.15f) {
            val px = (cx + pupilOffsetX).coerceIn(
                cx - eyeRadiusX * 0.45f, cx + eyeRadiusX * 0.45f
            )
            val py = (cy + pupilOffsetY).coerceIn(
                cy - ry * 0.40f, cy + ry * 0.40f
            )
            val pr = pupilRadius * eyeScaleY.coerceAtLeast(0.3f)
            canvas.drawCircle(px, py, pr, pupilPaint)

            // Tiny glint on pupil
            canvas.drawCircle(
                px - pr * 0.28f,
                py - pr * 0.32f,
                pr * 0.22f,
                pupilGlintPaint
            )
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Utility extension (no lambda import needed)
    // ─────────────────────────────────────────────────────────────

    private fun ValueAnimator.doOnEnd(action: () -> Unit) {
        addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                action()
            }
        })
    }
}
