package com.nybroadband.mobile.presentation.test

import android.content.Context
import android.graphics.BlurMaskFilter
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
 * Premium semi-circular speedometer gauge — 2025 redesign.
 *
 * Improvements over the original:
 *   • Thicker arc stroke (STROKE_FACTOR 0.072 → 0.096)
 *   • Glow layer behind the active arc via BlurMaskFilter
 *   • Brighter gradient (#60A5FA blue → #A78BFA purple → #F472B6 pink)
 *   • Thinner track so the coloured arc "floats" more dramatically
 *   • Wider needle with brighter white + stronger shadow
 *   • Larger pivot disc with stronger radial glow
 *   • Brighter tick labels (#7A82B8)
 *
 * Scale: logarithmic 0→1→5→10→25→50→100→250→500→1000 Mbps.
 * Arc: 240° starting from 7-o'clock (150° canvas coords).
 * Animation: Choreographer exponential smoothing, settles in ~300 ms.
 */
class SpeedometerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : View(context, attrs, defStyle) {

    // ── Constants ─────────────────────────────────────────────────────────────

    private val START_ANGLE = 150f
    private val SWEEP_ANGLE = 240f

    private val MAJOR_TICKS  = floatArrayOf(0f, 1f, 5f, 10f, 25f, 50f, 100f, 250f, 500f, 1000f)
    private val MAJOR_LABELS = arrayOf("0", "1", "5", "10", "25", "50", "100", "250", "500", "1000")
    private val MINOR_TICKS  = floatArrayOf(2f, 3f, 7f, 15f, 20f, 35f, 75f, 150f, 200f, 350f, 750f)

    companion object {
        /** Arc stroke width as fraction of radius — increased for bolder look. */
        private const val STROKE_FACTOR = 0.096f

        /** Track is narrower than arc so arc "floats" above the groove. */
        private const val TRACK_RATIO = 0.50f

        /** Label sits at radius + strokeWidth × LABEL_MULT from centre. */
        private const val LABEL_MULT = 2.2f

        private val LABEL_RADIUS_F = 1f + STROKE_FACTOR * LABEL_MULT  // ≈ 1.21

        /** Smoothing per Choreographer frame — settles within ~300 ms. */
        private const val SMOOTHING = 0.13f
    }

    // ── Choreographer animation ───────────────────────────────────────────────

    private var displayedFraction = 0f
    private var targetFraction    = 0f
    private var tickerRunning     = false

    private val frameTicker = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!isAttachedToWindow) { tickerRunning = false; return }
            val delta = targetFraction - displayedFraction
            if (abs(delta) < 0.0005f) {
                displayedFraction = targetFraction
                tickerRunning = false
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

    // ── Cached geometry ───────────────────────────────────────────────────────

    private val oval         = RectF()
    private val glowOval     = RectF()
    private var cachedCx     = -1f
    private var cachedRadius = -1f
    private var arcShader:   LinearGradient? = null
    private var glowShader:  LinearGradient? = null
    private var pivotShader: RadialGradient? = null

    // ── Paints ────────────────────────────────────────────────────────────────

    /** Dark groove track (narrower than arc). */
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style     = Paint.Style.STROKE
        color     = Color.parseColor("#1E1E3A")
        strokeCap = Paint.Cap.BUTT
    }

    /** Glow layer drawn slightly wider than the arc using BlurMaskFilter. */
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style     = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        // BlurMaskFilter radius set in onDraw when geometry is known
    }

    /** Active gradient arc. */
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style     = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    /** Major tick marks. */
    private val majorTickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#3A3A5C")
    }

    /** Minor tick marks. */
    private val minorTickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#252540")
    }

    /** Speed labels. */
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color     = Color.parseColor("#7A82B8")
        textAlign = Paint.Align.CENTER
    }

    /** Needle body — bright white. */
    private val needlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F1F5F9")   // slate-100 — slightly warm white
        style = Paint.Style.FILL
    }

    /** Needle shadow — stronger offset for depth. */
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#55000020")
        style = Paint.Style.FILL
    }

    /** Pivot disc. */
    private val pivotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E2E8F0")   // cool white pivot
        style = Paint.Style.FILL
    }

    /** Pivot glow halo. */
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

    fun setSpeed(mbps: Float) {
        targetFraction = speedToFraction(mbps.coerceIn(0f, 1000f))
        startTickerIfNeeded()
    }

    fun sweepToZero() {
        targetFraction = 0f
        startTickerIfNeeded()
    }

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
        val dp4 = 4f * resources.displayMetrics.density
        val cy  = h * 0.60f

        val radius = min(
            (cy - dp4) / LABEL_RADIUS_F,
            cx / (0.866f * LABEL_RADIUS_F),
        ).coerceAtMost(h * 0.43f)

        val strokeWidth = radius * STROKE_FACTOR
        val labelR      = radius + strokeWidth * LABEL_MULT

        trackPaint.strokeWidth     = strokeWidth * TRACK_RATIO
        arcPaint.strokeWidth       = strokeWidth
        glowPaint.strokeWidth      = strokeWidth * 1.8f   // glow is wider than arc
        majorTickPaint.strokeWidth = strokeWidth * 0.18f
        minorTickPaint.strokeWidth = strokeWidth * 0.11f
        labelPaint.textSize        = strokeWidth * 0.82f

        oval.set(cx - radius, cy - radius, cx + radius, cy + radius)
        glowOval.set(cx - radius, cy - radius, cx + radius, cy + radius)

        // ── Rebuild shaders only when geometry changes ────────────────────────
        if (cx != cachedCx || radius != cachedRadius) {
            cachedCx     = cx
            cachedRadius = radius

            // Arc gradient: blue-400 → violet-400 → pink-400 (more saturated than before)
            val arcColors = intArrayOf(
                Color.parseColor("#60A5FA"),   // blue-400
                Color.parseColor("#A78BFA"),   // violet-400
                Color.parseColor("#F472B6"),   // pink-400
            )

            arcShader = LinearGradient(
                cx - radius, cy, cx + radius, cy,
                arcColors,
                floatArrayOf(0f, 0.50f, 1f),
                Shader.TileMode.CLAMP,
            ).also { shader ->
                val m = Matrix()
                m.postRotate(START_ANGLE - 90f, cx, cy)
                shader.setLocalMatrix(m)
            }

            // Glow uses same colors but more transparent — creates a soft bloom
            glowShader = LinearGradient(
                cx - radius, cy, cx + radius, cy,
                intArrayOf(
                    Color.parseColor("#4060A5FA"),
                    Color.parseColor("#40A78BFA"),
                    Color.parseColor("#40F472B6"),
                ),
                floatArrayOf(0f, 0.50f, 1f),
                Shader.TileMode.CLAMP,
            ).also { shader ->
                val m = Matrix()
                m.postRotate(START_ANGLE - 90f, cx, cy)
                shader.setLocalMatrix(m)
            }

            // Pivot glow: purple radial fade
            val pivotR = strokeWidth * 0.55f
            val glowR  = pivotR * 3.2f
            pivotShader = RadialGradient(
                cx, cy, glowR,
                intArrayOf(Color.parseColor("#80A78BFA"), Color.TRANSPARENT),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP,
            )

            // Set blur on glowPaint (must be rebuilt with geometry)
            glowPaint.maskFilter = BlurMaskFilter(
                strokeWidth * 1.2f,
                BlurMaskFilter.Blur.NORMAL,
            )
        }

        // ── 1. Dark background track ──────────────────────────────────────────
        canvas.drawArc(oval, START_ANGLE, SWEEP_ANGLE, false, trackPaint)

        val sweep = displayedFraction * SWEEP_ANGLE

        // ── 2. Glow layer (drawn before arc, creates soft bloom behind it) ────
        if (sweep > 0.5f) {
            glowPaint.shader = glowShader
            canvas.drawArc(glowOval, START_ANGLE, sweep, false, glowPaint)
            glowPaint.shader = null
        }

        // ── 3. Active gradient arc ────────────────────────────────────────────
        if (sweep > 0.5f) {
            arcPaint.shader = arcShader
            canvas.drawArc(oval, START_ANGLE, sweep, false, arcPaint)
            arcPaint.shader = null
        }

        // ── 4. Minor ticks ────────────────────────────────────────────────────
        val minorOuter = radius + strokeWidth * 0.36f
        val minorInner = radius - strokeWidth * 0.36f
        for (mbps in MINOR_TICKS) {
            val angleDeg = fractionToAngleDeg(speedToFraction(mbps))
            val rad      = Math.toRadians(angleDeg.toDouble())
            val cosA = cos(rad).toFloat(); val sinA = sin(rad).toFloat()
            canvas.drawLine(
                cx + minorInner * cosA, cy + minorInner * sinA,
                cx + minorOuter * cosA, cy + minorOuter * sinA,
                minorTickPaint,
            )
        }

        // ── 5. Major ticks + labels ───────────────────────────────────────────
        val majorOuter = radius + strokeWidth * 0.60f
        val majorInner = radius - strokeWidth * 0.60f
        for (i in MAJOR_TICKS.indices) {
            val angleDeg = fractionToAngleDeg(speedToFraction(MAJOR_TICKS[i]))
            val rad      = Math.toRadians(angleDeg.toDouble())
            val cosA = cos(rad).toFloat(); val sinA = sin(rad).toFloat()

            canvas.drawLine(
                cx + majorInner * cosA, cy + majorInner * sinA,
                cx + majorOuter * cosA, cy + majorOuter * sinA,
                majorTickPaint,
            )
            canvas.drawText(
                MAJOR_LABELS[i],
                cx + labelR * cosA,
                cy + labelR * sinA + labelPaint.textSize * 0.36f,
                labelPaint,
            )
        }

        // ── 6. Needle ─────────────────────────────────────────────────────────
        val needleAngleDeg = fractionToAngleDeg(displayedFraction)
        val needleRad      = Math.toRadians(needleAngleDeg.toDouble())
        val perpRad        = needleRad + PI / 2.0

        val cosN = cos(needleRad).toFloat(); val sinN = sin(needleRad).toFloat()
        val cosP = cos(perpRad).toFloat();   val sinP = sin(perpRad).toFloat()

        val needleLen = radius * 0.86f       // tip reach
        val tailLen   = strokeWidth * 0.60f  // stub behind pivot
        val pivotHalf = strokeWidth * 0.22f  // wider for more presence
        val tipHalf   = strokeWidth * 0.02f

        val tipX  = cx + needleLen * cosN
        val tipY  = cy + needleLen * sinN
        val tailX = cx - tailLen * cosN
        val tailY = cy - tailLen * sinN

        val needlePath = Path().apply {
            moveTo(tailX + tipHalf * cosP,  tailY + tipHalf * sinP)
            lineTo(cx    + pivotHalf * cosP, cy   + pivotHalf * sinP)
            lineTo(tipX,                      tipY)
            lineTo(cx    - pivotHalf * cosP, cy   - pivotHalf * sinP)
            lineTo(tailX - tipHalf * cosP,  tailY - tipHalf * sinP)
            close()
        }

        // Stronger shadow offset = more depth
        canvas.save()
        canvas.translate(strokeWidth * 0.12f, strokeWidth * 0.12f)
        canvas.drawPath(needlePath, shadowPaint)
        canvas.restore()
        canvas.drawPath(needlePath, needlePaint)

        // ── 7. Pivot disc + glow ──────────────────────────────────────────────
        val pivotR = strokeWidth * 0.55f
        val glowR  = pivotR * 3.2f

        pivotShader?.let { shader ->
            pivotGlowPaint.shader = shader
            canvas.drawCircle(cx, cy, glowR, pivotGlowPaint)
        }
        canvas.drawCircle(cx, cy, pivotR, pivotPaint)
    }

    override fun onDetachedFromWindow() {
        tickerRunning = false
        super.onDetachedFromWindow()
    }
}
