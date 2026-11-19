package com.nova.assistant.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.sin

/**
 * Плавающий персонаж NOVA. Рисуется кодом (без картинок), поэтому легко
 * масштабируется и меняет состояние.
 *
 * Состояния управляют анимацией: дыхание, волна голоса, вращающиеся дуги,
 * радар поиска, линия сканирования, галочка, знак вопроса.
 */
class NovaCharacterView(context: Context) : View(context) {

    var state: CharacterState = CharacterState.IDLE
        set(value) {
            if (field != value) { field = value; invalidate() }
        }

    /** Уровень голоса 0..1 — для волны при слушании. */
    var level: Float = 0f
        set(value) { field = value.coerceIn(0f, 1f); invalidate() }

    var scaleFactor: Float = 1.0f
        set(value) { field = value.coerceIn(0.6f, 1.6f); invalidate() }

    var onTap: (() -> Unit)? = null
    var onDoubleTap: (() -> Unit)? = null
    var onLongPress: (() -> Unit)? = null
    var onDrag: ((dx: Float, dy: Float) -> Unit)? = null

    private val d = resources.displayMetrics.density
    private val primary = Color.parseColor("#6C8CFF")
    private val accent = Color.parseColor("#35E0C8")
    private val warn = Color.parseColor("#FF7A7A")

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#101728")
    }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * d
    }
    private val core = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = accent; strokeWidth = 3f * d; strokeCap = Paint.Cap.ROUND
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 16f * d; textAlign = Paint.Align.CENTER
    }

    private var phase = 0f
    private var appear = 0f
    private val loop = ValueAnimator.ofFloat(0f, (2 * Math.PI).toFloat()).apply {
        duration = 1200
        repeatCount = ValueAnimator.INFINITE
        addUpdateListener {
            phase = it.animatedValue as Float
            invalidate()
        }
    }

    private val appearAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 350
        addUpdateListener { appear = it.animatedValue as Float; invalidate() }
    }

    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var lastTapTime = 0L

    init {
        contentDescription = "NOVA — помощник"
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    fun start() {
        if (!loop.isStarted) loop.start()
        if (appearAnimator.isStarted) appearAnimator.cancel()
        appear = 1f
        appearAnimator.start()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        start()
    }

    override fun onDetachedFromWindow() {
        loop.cancel()
        appearAnimator.cancel()
        super.onDetachedFromWindow()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.action) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.rawX; downY = e.rawY; dragging = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.rawX - downX
                val dy = e.rawY - downY
                if (dragging || kotlin.math.abs(dx) > 12 * d || kotlin.math.abs(dy) > 12 * d) {
                    dragging = true
                    onDrag?.invoke(dx, dy)
                    downX = e.rawX; downY = e.rawY
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!dragging) {
                    val now = System.currentTimeMillis()
                    if (now - lastTapTime < 300) {
                        onDoubleTap?.invoke(); lastTapTime = 0
                    } else {
                        lastTapTime = now
                        postDelayed({ if (lastTapTime != 0L && System.currentTimeMillis() - lastTapTime >= 280) { onTap?.invoke(); lastTapTime = 0 } }, 300)
                    }
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> { dragging = false; return true }
        }
        return super.onTouchEvent(e)
    }

    override fun onDraw(c: Canvas) {
        val r = (30f * d) * scaleFactor * (0.85f + 0.15f * appear)
        val cx = width / 2f
        val cy = height / 2f
        val color = when (state) {
            CharacterState.ERROR -> warn
            CharacterState.DONE -> Color.parseColor("#5CE08A")
            else -> primary
        }

        // Внешнее свечение
        glow.color = color
        glow.alpha = (40 + 90 * (0.5f + 0.5f * sin(phase))).coerceIn(0, 255)
        c.drawCircle(cx, cy, r + 8 * d + 4 * d * sin(phase), glow)

        // Тело
        fill.color = Color.parseColor("#101728")
        c.drawCircle(cx, cy, r, fill)
        glow.alpha = 200
        glow.strokeWidth = 2f * d
        c.drawCircle(cx, cy, r, glow)

        // Внутреннее ядро
        core.color = color
        core.alpha = (60 + 80 * (0.5f + 0.5f * sin(phase * 1.3f))).coerceIn(0, 255)
        c.drawCircle(cx, cy, r * 0.55f, core)

        when (state) {
            CharacterState.IDLE -> drawIdle(c, cx, cy, r)
            CharacterState.LISTENING -> drawListening(c, cx, cy, r)
            CharacterState.THINKING -> drawThinking(c, cx, cy, r, color, 1f)
            CharacterState.THINK_HARD -> drawThinking(c, cx, cy, r, color, 2f)
            CharacterState.ACTING -> drawActing(c, cx, cy, r, color)
            CharacterState.SEARCHING -> drawSearching(c, cx, cy, r, accent)
            CharacterState.READING -> drawReading(c, cx, cy, r, accent)
            CharacterState.ERROR -> drawError(c, cx, cy, r)
            CharacterState.DONE -> drawDone(c, cx, cy, r)
            CharacterState.CONFIRM -> drawConfirm(c, cx, cy, r)
            CharacterState.HIDDEN -> Unit
        }
    }

    private fun drawIdle(c: Canvas, cx: Float, cy: Float, r: Float) {
        // Мягкая пульсация точки в центре.
        core.color = Color.WHITE
        core.alpha = (120 + 100 * (0.5f + 0.5f * sin(phase))).toInt()
        c.drawCircle(cx, cy, r * 0.14f, core)
    }

    private fun drawListening(c: Canvas, cx: Float, cy: Float, r: Float) {
        val amp = 0.25f + 0.75f * level
        for (i in 0 until 5) {
            val h = (r * 0.18f + r * 0.5f * amp * (0.5f + 0.5f * sin(phase * 2 + i * 0.9f)))
            val x = cx + (i - 2) * r * 0.28f
            c.drawLine(x, cy - h, x, cy + h, bar)
        }
    }

    private fun drawThinking(c: Canvas, cx: Float, cy: Float, r: Float, color: Int, rings: Float) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 2.5f * d; color = color
        }
        for (k in 0 until rings.toInt()) {
            val rr = r * (0.7f - 0.2f * k)
            val start = phase * (1f + 0.5f * k)
            val sweep = (2.0f * Math.PI / 3.0f).toFloat()
            val oval = RectF(cx - rr, cy - rr, cx + rr, cy + rr)
            c.drawArc(oval, start.toFloat(), sweep, false, p)
        }
    }

    private fun drawActing(c: Canvas, cx: Float, cy: Float, r: Float, color: Int) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 2.5f * d; color = color
        }
        val rr = r * 0.75f
        val oval = RectF(cx - rr, cy - rr, cx + rr, cy + rr)
        c.drawArc(oval, phase.toFloat(), 120f, false, p)
        val a = phase
        c.drawCircle(cx + rr * kotlin.math.cos(a), cy + rr * kotlin.math.sin(a), 4 * d, core)
    }

    private fun drawSearching(c: Canvas, cx: Float, cy: Float, r: Float, color: Int) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 2f * d; color = color; alpha = 160
        }
        c.drawCircle(cx, cy, r * 0.8f, p)
        val a = phase
        val path = Path().apply {
            moveTo(cx, cy)
            lineTo(cx + r * 0.8f * kotlin.math.cos(a), cy + r * 0.8f * kotlin.math.sin(a))
        }
        c.drawPath(path, p)
    }

    private fun drawReading(c: Canvas, cx: Float, cy: Float, r: Float, color: Int) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 3f * d; color = color
        }
        val y = cy - r * 0.6f + ((phase / (2 * Math.PI)) % 1f) * r * 1.2f
        c.drawLine(cx - r * 0.6f, y, cx + r * 0.6f, y, p)
        c.drawCircle(cx, cy, r * 0.8f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 1.5f * d; color = color; alpha = 120
        })
    }

    private fun drawError(c: Canvas, cx: Float, cy: Float, r: Float) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = warn; strokeWidth = 3.5f * d; strokeCap = Paint.Cap.ROUND
        }
        val s = r * 0.35f
        c.drawLine(cx - s, cy - s, cx + s, cy + s, p)
        c.drawLine(cx + s, cy - s, cx - s, cy + s, p)
    }

    private fun drawDone(c: Canvas, cx: Float, cy: Float, r: Float) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#5CE08A"); strokeWidth = 4f * d; strokeCap = Paint.Cap.ROUND
        }
        val s = r * 0.4f
        val path = Path().apply {
            moveTo(cx - s, cy)
            lineTo(cx - s * 0.25f, cy + s * 0.7f)
            lineTo(cx + s, cy - s * 0.7f)
        }
        c.drawPath(path, p)
    }

    private fun drawConfirm(c: Canvas, cx: Float, cy: Float, r: Float) {
        textPaint.color = Color.WHITE
        textPaint.textSize = r * 0.9f
        c.drawText("?", cx, cy + textPaint.textSize * 0.35f, textPaint)
    }
}
