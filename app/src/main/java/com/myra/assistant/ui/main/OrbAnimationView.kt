package com.myra.assistant.ui.main

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.LinearInterpolator
import kotlin.math.cos
import kotlin.math.sin

class OrbAnimationView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class OrbState {
        IDLE,
        LISTENING,
        SPEAKING,
        THINKING,
        ACTIVE
    }

    private var currentState: OrbState = OrbState.IDLE
    private var currentAmplitude: Float = 0f

    // Animators
    private var pulseScale: Float = 1.0f
    private var glowAlpha: Int = 140
    private var rotationAngle: Float = 0f
    private var waveOffset: Float = 0f
    private var thinkingAngle: Float = 0f
    private var particleOrbitAngle: Float = 0f

    private var pulseAnimator: ValueAnimator? = null
    private var rotateAnimator: ValueAnimator? = null
    private var waveAnimator: ValueAnimator? = null
    private var thinkingAnimator: ValueAnimator? = null
    private var particleAnimator: ValueAnimator? = null

    // Paints
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
    }
    private val thinkingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        strokeCap = Paint.Cap.ROUND
    }
    private val particlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val ringRect = RectF()
    private val thinkingRect = RectF()
    private val wavePath = Path()

    init {
        initAnimators()
    }

    private fun initAnimators() {
        // Idle / ambient pulse
        pulseAnimator = ValueAnimator.ofFloat(1.0f, 1.15f).apply {
            duration = 1500L
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                pulseScale = it.animatedValue as Float
                glowAlpha = (120 + (pulseScale - 1.0f) * 666).toInt().coerceIn(100, 255)
                invalidate()
            }
        }

        // Ring rotation
        rotateAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 4000L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                rotationAngle = it.animatedValue as Float
                invalidate()
            }
        }

        // Wave motion
        waveAnimator = ValueAnimator.ofFloat(0f, Math.PI.toFloat() * 2f).apply {
            duration = 2000L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                waveOffset = it.animatedValue as Float
                invalidate()
            }
        }

        // Thinking spinner
        thinkingAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 1000L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                thinkingAngle = it.animatedValue as Float
                invalidate()
            }
        }

        // Particles orbit
        particleAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 6000L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                particleOrbitAngle = it.animatedValue as Float
                invalidate()
            }
        }

        startAnimators()
    }

    private fun startAnimators() {
        pulseAnimator?.start()
        rotateAnimator?.start()
        waveAnimator?.start()
        particleAnimator?.start()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startAnimators()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        pulseAnimator?.cancel()
        rotateAnimator?.cancel()
        waveAnimator?.cancel()
        thinkingAnimator?.cancel()
        particleAnimator?.cancel()
    }

    fun setState(state: OrbState) {
        if (currentState == state) return
        currentState = state

        if (state == OrbState.THINKING) {
            thinkingAnimator?.start()
        } else {
            thinkingAnimator?.cancel()
        }

        if (state == OrbState.SPEAKING) {
            waveAnimator?.duration = 1000L
        } else {
            waveAnimator?.duration = 2000L
        }

        invalidate()
    }

    fun getState(): OrbState = currentState

    fun setAmplitude(rms: Float) {
        currentAmplitude = rms.coerceIn(0f, 1f)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val cx = width / 2f
        val cy = height / 2f
        val baseRadius = (minOf(width, height) / 2f) * 0.55f

        if (baseRadius <= 0) return

        val (colorStart, colorEnd) = getColorsForState(currentState)

        val dynamicScale = when (currentState) {
            OrbState.SPEAKING -> 1.0f + (currentAmplitude * 0.25f)
            OrbState.LISTENING -> 1.0f + (currentAmplitude * 0.15f)
            else -> pulseScale
        }

        val orbRadius = baseRadius * dynamicScale

        // Layer 1: Radial Glow
        drawGlow(canvas, cx, cy, orbRadius * 1.6f, colorStart, colorEnd)

        // Layer 2: Core Orb
        drawCoreOrb(canvas, cx, cy, orbRadius, colorStart, colorEnd)

        // Layer 3: Rotating Rings (Dashed arcs)
        drawRotatingRings(canvas, cx, cy, orbRadius, colorStart, colorEnd)

        // Layer 4: Wave Rings
        drawWaveRings(canvas, cx, cy, orbRadius, colorStart)

        // Layer 5: Thinking Arc (Only in THINKING state)
        if (currentState == OrbState.THINKING) {
            drawThinkingArc(canvas, cx, cy, orbRadius)
        }

        // Layer 6: Orbiting Particles (Active, Speaking, Listening)
        if (currentState != OrbState.IDLE) {
            drawParticles(canvas, cx, cy, orbRadius, colorEnd)
        }

        // Layer 7: Inner Highlight (Top-left 3D sphere shine)
        drawInnerHighlight(canvas, cx, cy, orbRadius)
    }

    private fun getColorsForState(state: OrbState): Pair<Int, Int> {
        return when (state) {
            OrbState.IDLE -> Pair(Color.parseColor("#B71C1C"), Color.parseColor("#880E4F"))
            OrbState.ACTIVE, OrbState.LISTENING -> Pair(Color.parseColor("#FF1744"), Color.parseColor("#D500F9"))
            OrbState.SPEAKING -> Pair(Color.parseColor("#E040FB"), Color.parseColor("#FF1744"))
            OrbState.THINKING -> Pair(Color.parseColor("#40C4FF"), Color.parseColor("#00B0FF"))
        }
    }

    private fun drawGlow(canvas: Canvas, cx: Float, cy: Float, glowRadius: Float, startCol: Int, endCol: Int) {
        val glowColor1 = Color.argb(
            (glowAlpha * 0.6f).toInt().coerceIn(0, 255),
            Color.red(startCol),
            Color.green(startCol),
            Color.blue(startCol)
        )
        val glowColor2 = Color.argb(
            (glowAlpha * 0.25f).toInt().coerceIn(0, 255),
            Color.red(endCol),
            Color.green(endCol),
            Color.blue(endCol)
        )
        val glowShader = RadialGradient(
            cx, cy, glowRadius,
            intArrayOf(glowColor1, glowColor2, Color.TRANSPARENT),
            floatArrayOf(0f, 0.6f, 1f),
            Shader.TileMode.CLAMP
        )
        glowPaint.shader = glowShader
        canvas.drawCircle(cx, cy, glowRadius, glowPaint)
    }

    private fun drawCoreOrb(canvas: Canvas, cx: Float, cy: Float, radius: Float, startCol: Int, endCol: Int) {
        val coreShader = RadialGradient(
            cx - radius * 0.25f,
            cy - radius * 0.25f,
            radius * 1.25f,
            intArrayOf(startCol, endCol, Color.parseColor("#150005")),
            floatArrayOf(0f, 0.7f, 1f),
            Shader.TileMode.CLAMP
        )
        corePaint.shader = coreShader
        canvas.drawCircle(cx, cy, radius, corePaint)
    }

    private fun drawRotatingRings(canvas: Canvas, cx: Float, cy: Float, radius: Float, color1: Int, color2: Int) {
        ringPaint.color = color1
        ringPaint.alpha = 180

        // Ring 1
        val r1 = radius * 1.22f
        ringRect.set(cx - r1, cy - r1, cx + r1, cy + r1)
        ringPaint.pathEffect = DashPathEffect(floatArrayOf(24f, 16f), 0f)
        canvas.save()
        canvas.rotate(rotationAngle, cx, cy)
        canvas.drawArc(ringRect, 0f, 360f, false, ringPaint)
        canvas.restore()

        // Ring 2 (opposite rotation)
        val r2 = radius * 1.38f
        ringPaint.color = color2
        ringPaint.alpha = 140
        ringRect.set(cx - r2, cy - r2, cx + r2, cy + r2)
        ringPaint.pathEffect = DashPathEffect(floatArrayOf(16f, 20f), 0f)
        canvas.save()
        canvas.rotate(-rotationAngle * 0.7f, cx, cy)
        canvas.drawArc(ringRect, 0f, 360f, false, ringPaint)
        canvas.restore()

        // Ring 3
        val r3 = radius * 1.52f
        ringPaint.color = color1
        ringPaint.alpha = 90
        ringRect.set(cx - r3, cy - r3, cx + r3, cy + r3)
        ringPaint.pathEffect = DashPathEffect(floatArrayOf(30f, 30f), 0f)
        canvas.save()
        canvas.rotate(rotationAngle * 0.4f, cx, cy)
        canvas.drawArc(ringRect, 0f, 360f, false, ringPaint)
        canvas.restore()

        ringPaint.pathEffect = null
    }

    private fun drawWaveRings(canvas: Canvas, cx: Float, cy: Float, radius: Float, color: Int) {
        wavePaint.color = color
        wavePaint.alpha = (100 + currentAmplitude * 155).toInt().coerceIn(80, 255)

        wavePath.reset()
        val numPoints = 60
        val baseR = radius * 1.15f
        val waveAmplitude = 4f + (currentAmplitude * 14f)

        for (i in 0..numPoints) {
            val angle = (i.toFloat() / numPoints) * Math.PI.toFloat() * 2f
            val r = baseR + sin(angle * 4 + waveOffset) * waveAmplitude
            val px = cx + cos(angle) * r
            val py = cy + sin(angle) * r
            if (i == 0) {
                wavePath.moveTo(px, py)
            } else {
                wavePath.lineTo(px, py)
            }
        }
        wavePath.close()
        canvas.drawPath(wavePath, wavePaint)
    }

    private fun drawThinkingArc(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        val r = radius * 1.25f
        thinkingRect.set(cx - r, cy - r, cx + r, cy + r)
        thinkingPaint.color = Color.parseColor("#40C4FF")
        thinkingPaint.alpha = 240

        canvas.save()
        canvas.rotate(thinkingAngle, cx, cy)
        canvas.drawArc(thinkingRect, 0f, 80f, false, thinkingPaint)
        canvas.drawArc(thinkingRect, 180f, 80f, false, thinkingPaint)
        canvas.restore()
    }

    private fun drawParticles(canvas: Canvas, cx: Float, cy: Float, radius: Float, color: Int) {
        val particleCount = 12
        val orbitRadius = radius * 1.32f + (currentAmplitude * 16f)

        for (i in 0 until particleCount) {
            val baseAngle = (i.toFloat() / particleCount) * 360f
            val angleDeg = (baseAngle + particleOrbitAngle + (i * 15f)) % 360f
            val angleRad = Math.toRadians(angleDeg.toDouble())

            val px = cx + (cos(angleRad) * orbitRadius).toFloat()
            val py = cy + (sin(angleRad) * (orbitRadius * 0.85f)).toFloat()

            val pRadius = 2.5f + ((i % 3) * 1.2f) + (currentAmplitude * 2f)
            val pAlpha = (140 + (sin(angleRad) * 100)).toInt().coerceIn(40, 255)

            particlePaint.color = color
            particlePaint.alpha = pAlpha
            canvas.drawCircle(px, py, pRadius, particlePaint)
        }
    }

    private fun drawInnerHighlight(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        val hx = cx - radius * 0.35f
        val hy = cy - radius * 0.35f
        val hRadius = radius * 0.5f

        val highlightShader = RadialGradient(
            hx, hy, hRadius,
            intArrayOf(Color.argb(180, 255, 255, 255), Color.argb(40, 255, 255, 255), Color.TRANSPARENT),
            floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP
        )
        highlightPaint.shader = highlightShader
        canvas.drawCircle(hx, hy, hRadius, highlightPaint)
    }
}
