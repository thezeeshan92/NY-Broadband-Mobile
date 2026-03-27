package com.nybroadband.mobile.presentation.test

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.sin

/**
 * CoverageMap-style semi-circular speedometer gauge.
 *
 * Scale is logarithmic: 0 → 1 → 5 → 10 → 25 → 50 → 100 → 250 → 500 → 1000 Mbps.
 * Call [setSpeed] to smoothly animate the needle to a new Mbps value.
 *
 * The gauge sweeps 240° starting from the 7-o'clock position.
 */
class SpeedometerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : View(context, attrs, defStyle) {

    private val START_ANGLE = 150f   // degrees (standard: 0° = 3-o'clock)
    private val SWEEP_ANGLE = 240f

    private val TICKS = floatArrayOf(0f, 1f, 5f, 10f, 25f, 50f, 100f, 250f, 500f, 1000f)
    private val LABELS = arrayOf("0", "1", "5", "10", "25", "50", "100", "250", "500", "1000")

    // Currently displayed fraction [0..1], driven by animator
    private var displayedFraction = 0f
    private var animator: ValueAnimator? = null

    private val oval = RectF()

    // ── Paints ────────────────────────────────────────────────────────────────

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#2A2A3E")
        strokeCap = Paint.Cap.BUTT
    }

    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#4A4A60")
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#8B8FA8")
        textAlign = Paint.Align.CENTER
    }

    private val needlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    private val needleShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#40000000")
        style = Paint.Style.FILL
    }

    private val pivotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4FC3F7")
        style = Paint.Style.FILL
    }

    // ── Log scale conversion ──────────────────────────────────────────────────

    private fun speedToFraction(mbps: Float): Float {
        if (mbps <= 0f) return 0f
        return (ln(mbps + 1f) / ln(1001f)).coerceIn(0f, 1f)
    }

    private fun fractionToAngleDeg(f: Float) = START_ANGLE + f * SWEEP_ANGLE

    // ── Public API ────────────────────────────────────────────────────────────

    fun setSpeed(mbps: Float, animate: Boolean = true) {
        val target = speedToFraction(mbps.coerceIn(0f, 1000f))
        animator?.cancel()
        if (animate && isAttachedToWindow) {
            animator = ValueAnimator.ofFloat(displayedFraction, target).apply {
                duration = 280L
                interpolator = DecelerateInterpolator()
                addUpdateListener {
                    displayedFraction = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        } else {
            displayedFraction = target
            invalidate()
        }
    }

    fun reset() {
        animator?.cancel()
        displayedFraction = 0f
        invalidate()
    }

    // ── Drawing ───────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        if (w == 0f || h == 0f) return

        // Bias the visual center slightly downward so arc labels have room at the top
        val cx = w * 0.5f
        val cy = h * 0.58f

        // Radius: fit within the view, leaving room for tick labels
        val maxRadius = min(w * 0.42f, h * 0.75f)
        val radius = maxRadius
        val strokeWidth = radius * 0.14f

        trackPaint.strokeWidth = strokeWidth
        arcPaint.strokeWidth = strokeWidth

        oval.set(cx - radius, cy - radius, cx + radius, cy + radius)

        // 1) Dark track
        canvas.drawArc(oval, START_ANGLE, SWEEP_ANGLE, false, trackPaint)

        // 2) Colored arc up to current speed
        val sweep = displayedFraction * SWEEP_ANGLE
        if (sweep > 1f) {
            // Gradient from purple → cyan, oriented left → right
            val gradShader = LinearGradient(
                cx - radius, cy,
                cx + radius, cy,
                intArrayOf(
                    Color.parseColor("#6C63FF"),
                    Color.parseColor("#4FC3F7"),
                ),
                null,
                Shader.TileMode.CLAMP,
            )
            // Rotate the gradient to align with arc start angle
            val matrix = Matrix()
            matrix.postRotate(START_ANGLE - 90f, cx, cy)
            gradShader.setLocalMatrix(matrix)
            arcPaint.shader = gradShader
            canvas.drawArc(oval, START_ANGLE, sweep, false, arcPaint)
            arcPaint.shader = null
        }

        // 3) Tick marks + labels
        val outerTickR = radius + strokeWidth * 0.55f
        val innerTickR = radius - strokeWidth * 0.55f
        val labelR = radius + strokeWidth * 2.1f
        tickPaint.strokeWidth = strokeWidth * 0.13f
        labelPaint.textSize = strokeWidth * 0.82f

        for (i in TICKS.indices) {
            val f = speedToFraction(TICKS[i])
            val angleDeg = fractionToAngleDeg(f)
            val angleRad = Math.toRadians(angleDeg.toDouble())
            val cosA = cos(angleRad).toFloat()
            val sinA = sin(angleRad).toFloat()

            canvas.drawLine(
                cx + innerTickR * cosA, cy + innerTickR * sinA,
                cx + outerTickR * cosA, cy + outerTickR * sinA,
                tickPaint,
            )

            val lx = cx + labelR * cosA
            val ly = cy + labelR * sinA + labelPaint.textSize * 0.38f
            canvas.drawText(LABELS[i], lx, ly, labelPaint)
        }

        // 4) Needle (tapered triangle from center to tip)
        val needleAngleDeg = fractionToAngleDeg(displayedFraction)
        val needleRad = Math.toRadians(needleAngleDeg.toDouble())
        val needleLen = radius * 0.78f
        val baseHalf = strokeWidth * 0.24f
        val perpRad = needleRad + Math.PI / 2.0

        val tipX = cx + needleLen * cos(needleRad).toFloat()
        val tipY = cy + needleLen * sin(needleRad).toFloat()
        val b1x = cx + baseHalf * cos(perpRad).toFloat()
        val b1y = cy + baseHalf * sin(perpRad).toFloat()
        val b2x = cx - baseHalf * cos(perpRad).toFloat()
        val b2y = cy - baseHalf * sin(perpRad).toFloat()

        val needlePath = Path().apply {
            moveTo(tipX, tipY)
            lineTo(b1x, b1y)
            lineTo(b2x, b2y)
            close()
        }

        // Shadow
        canvas.save()
        canvas.translate(strokeWidth * 0.08f, strokeWidth * 0.08f)
        canvas.drawPath(needlePath, needleShadowPaint)
        canvas.restore()

        canvas.drawPath(needlePath, needlePaint)

        // 5) Pivot dot at center
        canvas.drawCircle(cx, cy, strokeWidth * 0.46f, pivotPaint)
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        super.onDetachedFromWindow()
    }
}