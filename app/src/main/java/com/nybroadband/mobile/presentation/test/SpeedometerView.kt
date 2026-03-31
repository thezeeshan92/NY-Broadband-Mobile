package com.nybroadband.mobile.presentation.test

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.Choreographer
import android.view.View
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.sin

/**
 * CoverageMap-style semi-circular speedometer gauge.
 *
 * ── Scale ─────────────────────────────────────────────────────────────────────
 * Logarithmic: 0 → 1 → 5 → 10 → 25 → 50 → 100 → 250 → 500 → 1000 Mbps.
 * Arc sweeps 240° starting from the 7-o'clock position (150° in canvas coords
 * where 0° = 3-o'clock, angles increase clockwise).
 *
 * ── Animation ─────────────────────────────────────────────────────────────────
 * Uses Choreographer-driven exponential smoothing instead of ValueAnimator.
 * Every vsync frame the displayed fraction moves 18% of its remaining distance
 * toward the target → settles in ≈250 ms, handles arbitrarily-frequent setSpeed()
 * calls without jerking, and stops automatically when the target is reached.
 *
 * ── Sizing ────────────────────────────────────────────────────────────────────
 * Radius is computed from view geometry so all tick labels remain inside the view
 * on every screen size. See the constraint derivation in onDraw.
 */
class SpeedometerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : View(context, attrs, defStyle) {

    // ── Constants ─────────────────────────────────────────────────────────────

    private val START_ANGLE = 150f
    private val SWEEP_ANGLE = 240f

    /** Major tick positions that receive a speed label. */
    private val MAJOR_TICKS  = floatArrayOf(0f, 1f, 5f, 10f, 25f, 50f, 100f, 250f, 500f, 1000f)
    private val MAJOR_LABELS = arrayOf("0", "1", "5", "10", "25", "50", "100", "250", "500", "1000")

    /** Minor (unlabelled) ticks for visual density between major labels. */
    private val MINOR_TICKS = floatArrayOf(2f, 3f, 7f, 15f, 20f, 35f, 75f, 150f, 200f, 350f, 750f)

    companion object {
        /** Arc stroke width as a fraction of radius. Thinner = precision-instrument feel. */
        private const val STROKE_FACTOR = 0.072f

        /** label sits at radius + strokeWidth × LABEL_MULT from centre. */
        private const val LABEL_MULT = 2.3f

        /**
         * Combined radius multiplier used in constraint maths:
         *   labelR = radius × LABEL_RADIUS_F
         */
        private val LABEL_RADIUS_F = 1f + STROKE_FACTOR * LABEL_MULT  // ≈ 1.166

        /**
         * Exponential-smoothing factor applied per Choreographer frame (~16 ms @ 60 fps).
         * 11% per frame → needle reaches within 0.05% of target in ≈ 400 ms.
         * With EMA pre-smoothing applied upstream (DISPLAY_ALPHA=0.28), the target itself
         * moves slowly — the needle now tracks a calm, analogue-like trajectory.
         */
        private const val SMOOTHING = 0.11f
    }

    // ── Choreographer-driven needle animation ─────────────────────────────────

    /** Currently rendered gauge fraction [0..1]. */
    private var displayedFraction = 0f

    /** Fraction the needle is homing toward. Updated by [setSpeed] / [sweepToZero]. */
    private var targetFraction = 0f

    private var tickerRunning = false

    private val frameTicker = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!isAttachedToWindow) {
                tickerRunning = false
                return
            }
            val delta = targetFraction - displayedFraction
            if (abs(delta) < 0.0005f) {
                displayedFraction = targetFraction
                tickerRunning = false          // target reached — stop ticking
            } else {
                displayedFraction += delta * SMOOTHING
                invalidate()
                Choreographer.getInstance().postFrameCallback(this)
            }
        }
    }

    private fun startTickerIfNeeded() {
        if (!tickerRunning && isAttachedToWindow) {
            tickerRunning = true
            Choreographer.getInstance().postFrameCallback(frameTicker)
        }
    }

    // ── Cached draw objects (rebuilt only on geometry change, not every frame) ─

    private val oval          = RectF()
    private var cachedCx      = -1f
    private var cachedRadius  = -1f
    private var arcShader:   LinearGradient?  = null
    private var pivotShader: RadialGradient?  = null

    // ── Paints ────────────────────────────────────────────────────────────────

    /** Unfilled arc track. */
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style     = Paint.Style.STROKE
        color     = Color.parseColor("#1A1A2E")
        strokeCap = Paint.Cap.BUTT
    }

    /** Gradient-filled arc up to the current speed. */
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style     = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    /** Major tick marks at labelled speed values. */
    private val majorTickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#3E3E5C")
    }

    /** Minor tick marks between major labels. */
    private val minorTickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#28283E")
    }

    /** Speed labels on the outer rim. */
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color     = Color.parseColor("#6870A0")
        textAlign = Paint.Align.CENTER
    }

    /** Needle body. */
    private val needlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    /** Needle drop-shadow. */
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#40000000")
        style = Paint.Style.FILL
    }

    /** Solid white pivot disc. */
    private val pivotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    /** Glow halo behind the pivot disc. */
    private val pivotGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    // ── Log-scale helpers ─────────────────────────────────────────────────────

    private fun speedToFraction(mbps: Float): Float {
        if (mbps <= 0f) return 0f
        return (ln(mbps + 1f) / ln(1001f)).coerceIn(0f, 1f)
    }

    private fun fractionToAngleDeg(f: Float) = START_ANGLE + f * SWEEP_ANGLE

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Smoothly animate the needle to [mbps] Mbps.
     * Safe to call at any frequency — the Choreographer ticker handles
     * arbitrarily rapid updates without jerking.
     */
    fun setSpeed(mbps: Float) {
        targetFraction = speedToFraction(mbps.coerceIn(0f, 1000f))
        startTickerIfNeeded()
    }

    /**
     * Glide the needle back to zero gracefully.
     * Call this at the start of the upload phase so the needle doesn't snap
     * from the download reading to zero.
     */
    fun sweepToZero() {
        targetFraction = 0f
        startTickerIfNeeded()
    }

    /**
     * Instantly reset the needle to zero without animation.
     * Used when a new test starts from the [Idle] state.
     */
    fun reset() {
        targetFraction    = 0f
        displayedFraction = 0f
        tickerRunning     = false
        invalidate()
    }

    // ── Drawing ───────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        if (w < 10f || h < 10f) return

        val cx = w * 0.5f

        // ── Radius constraint derivation ──────────────────────────────────────
        //
        // The arc spans 150°→390°(=30°). Extreme tick label positions:
        //   • Top   (~270°, ≈25 Mbps): y = cy − labelR
        //   • Left  (150°,   0 Mbps):  x = cx − labelR·cos30° = cx − labelR·0.866
        //   • Right ( 30°, 1000 Mbps): x = cx + labelR·cos30° = cx + labelR·0.866
        //
        // Where labelR = radius × LABEL_RADIUS_F
        //
        // Constraints (4 dp margin from each edge):
        //   top:        cy − labelR  ≥ dp4   →  radius ≤ (cy − dp4) / LABEL_RADIUS_F
        //   left/right: labelR·0.866 ≤ cx    →  radius ≤ cx / (0.866 × LABEL_RADIUS_F)
        //
        // cy = h × 0.60 biases the centre downward so the upper arc has breathing room.
        // The radius is also capped at 43% of view height to keep the gauge from
        // appearing oversized on very tall constrained views.

        val dp4    = 4f * resources.displayMetrics.density
        val cy     = h * 0.60f
        val radius = min(
            (cy - dp4) / LABEL_RADIUS_F,
            cx / (0.866f * LABEL_RADIUS_F),
        ).coerceAtMost(h * 0.43f)

        val strokeWidth = radius * STROKE_FACTOR
        val labelR      = radius + strokeWidth * LABEL_MULT

        // Track is 65% the width of the arc so the coloured arc "floats" above
        // the dark groove — the visual contrast gives a premium, layered look.
        trackPaint.strokeWidth     = strokeWidth * 0.65f
        arcPaint.strokeWidth       = strokeWidth
        majorTickPaint.strokeWidth = strokeWidth * 0.17f
        minorTickPaint.strokeWidth = strokeWidth * 0.11f
        labelPaint.textSize        = strokeWidth * 0.80f

        oval.set(cx - radius, cy - radius, cx + radius, cy + radius)

        // ── Rebuild shaders when geometry changes (not every frame) ───────────
        if (cx != cachedCx || radius != cachedRadius) {
            cachedCx     = cx
            cachedRadius = radius

            // Arc gradient: blue-500 → violet-500 → pink-500  (Material You accent range)
            arcShader = LinearGradient(
                cx - radius, cy,
                cx + radius, cy,
                intArrayOf(
                    Color.parseColor("#3B82F6"),   // blue-500
                    Color.parseColor("#8B5CF6"),   // violet-500
                    Color.parseColor("#EC4899"),   // pink-500
                ),
                floatArrayOf(0f, 0.50f, 1f),
                Shader.TileMode.CLAMP,
            ).also { shader ->
                val m = Matrix()
                m.postRotate(START_ANGLE - 90f, cx, cy)
                shader.setLocalMatrix(m)
            }

            // Pivot glow: blue-ish radial fade
            val pivotR = strokeWidth * 0.48f
            val glowR  = pivotR * 2.8f
            pivotShader = RadialGradient(
                cx, cy, glowR,
                intArrayOf(Color.parseColor("#705B8AF5"), Color.TRANSPARENT),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP,
            )
        }

        // ── 1. Dark background track ──────────────────────────────────────────
        canvas.drawArc(oval, START_ANGLE, SWEEP_ANGLE, false, trackPaint)

        // ── 2. Coloured arc up to the current displayed speed ─────────────────
        val sweep = displayedFraction * SWEEP_ANGLE
        if (sweep > 0.5f) {
            arcPaint.shader = arcShader
            canvas.drawArc(oval, START_ANGLE, sweep, false, arcPaint)
            arcPaint.shader = null
        }

        // ── 3. Minor tick marks (shorter, dimmer) ─────────────────────────────
        val minorOuter = radius + strokeWidth * 0.38f
        val minorInner = radius - strokeWidth * 0.38f
        for (mbps in MINOR_TICKS) {
            val angleDeg = fractionToAngleDeg(speedToFraction(mbps))
            val rad      = Math.toRadians(angleDeg.toDouble())
            val cosA     = cos(rad).toFloat()
            val sinA     = sin(rad).toFloat()
            canvas.drawLine(
                cx + minorInner * cosA, cy + minorInner * sinA,
                cx + minorOuter * cosA, cy + minorOuter * sinA,
                minorTickPaint,
            )
        }

        // ── 4. Major tick marks + speed labels ────────────────────────────────
        val majorOuter = radius + strokeWidth * 0.58f
        val majorInner = radius - strokeWidth * 0.58f
        for (i in MAJOR_TICKS.indices) {
            val angleDeg = fractionToAngleDeg(speedToFraction(MAJOR_TICKS[i]))
            val rad      = Math.toRadians(angleDeg.toDouble())
            val cosA     = cos(rad).toFloat()
            val sinA     = sin(rad).toFloat()

            canvas.drawLine(
                cx + majorInner * cosA, cy + majorInner * sinA,
                cx + majorOuter * cosA, cy + majorOuter * sinA,
                majorTickPaint,
            )

            // Label: offset by 36% of text size for optical vertical centering
            canvas.drawText(
                MAJOR_LABELS[i],
                cx + labelR * cosA,
                cy + labelR * sinA + labelPaint.textSize * 0.36f,
                labelPaint,
            )
        }

        // ── 5. Needle — slim tapered shape with a short tail behind the pivot ─
        //
        // Shape (cross-section along the needle axis):
        //   tail (behind pivot, almost a point)
        //   └─ widens to pivot width
        //      └─ tapers to a near-point at the tip
        //
        // This gives the classic analog-gauge "lollipop needle" silhouette.

        val needleAngleDeg = fractionToAngleDeg(displayedFraction)
        val needleRad      = Math.toRadians(needleAngleDeg.toDouble())
        val perpRad        = needleRad + PI / 2.0

        val cosN = cos(needleRad).toFloat()
        val sinN = sin(needleRad).toFloat()
        val cosP = cos(perpRad).toFloat()
        val sinP = sin(perpRad).toFloat()

        val needleLen = radius * 0.83f       // tip at 83% of radius
        val tailLen   = strokeWidth * 0.55f  // stub behind pivot
        val pivotHalf = strokeWidth * 0.17f  // widest point (at pivot)
        val tipHalf   = strokeWidth * 0.02f  // near-zero at tip

        val tipX  = cx + needleLen * cosN
        val tipY  = cy + needleLen * sinN
        val tailX = cx - tailLen * cosN
        val tailY = cy - tailLen * sinN

        val needlePath = Path().apply {
            moveTo(tailX + tipHalf * cosP,  tailY + tipHalf * sinP)
            lineTo(cx     + pivotHalf * cosP, cy    + pivotHalf * sinP)
            lineTo(tipX,                       tipY)
            lineTo(cx     - pivotHalf * cosP, cy    - pivotHalf * sinP)
            lineTo(tailX  - tipHalf * cosP,  tailY  - tipHalf * sinP)
            close()
        }

        // Subtle drop-shadow (offset diagonally)
        canvas.save()
        canvas.translate(strokeWidth * 0.08f, strokeWidth * 0.08f)
        canvas.drawPath(needlePath, shadowPaint)
        canvas.restore()

        canvas.drawPath(needlePath, needlePaint)

        // ── 6. Pivot disc with radial glow ────────────────────────────────────
        val pivotR = strokeWidth * 0.48f
        val glowR  = pivotR * 2.8f

        pivotShader?.let { shader ->
            pivotGlowPaint.shader = shader
            canvas.drawCircle(cx, cy, glowR, pivotGlowPaint)
        }
        canvas.drawCircle(cx, cy, pivotR, pivotPaint)
    }

    override fun onDetachedFromWindow() {
        // frameTicker checks isAttachedToWindow and self-stops on the next frame.
        tickerRunning = false
        super.onDetachedFromWindow()
    }
}