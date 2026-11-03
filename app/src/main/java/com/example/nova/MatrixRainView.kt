package com.example.nova

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.SystemClock
import android.provider.Settings
import android.util.AttributeSet
import android.view.View
import java.util.Random
import kotlin.math.max

/**
 * Цифровой дождь темы Matrix.
 *
 * Рисуется на `Canvas`, без WebView, GIF и полноэкранного растра: на Pixel 7 Pro
 * это десятки колонок и несколько сотен глифов, а не мегабайты памяти. Частота
 * ограничена 30 кадрами в секунду; на слабом устройстве, при отключённой
 * анимации или `Animator duration scale = 0` остаётся статичный кадр.
 *
 * Вид не запускает себя сам: [startRain] зовёт главный экран только тогда,
 * когда Matrix выбран и фон действительно виден. Это важно для батареи —
 * невидимый View не должен крутить `postInvalidateOnAnimation`.
 */
class MatrixRainView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private data class Column(
        var headY: Float,
        var speed: Float,
        var trail: Int,
        var glyphOffset: Int,
    )

    private val density = resources.displayMetrics.density
    private val glyphs = "01アイウエオカキクケコサシスセソタチツテトナニヌネノハヒフヘホマミムメモヤユヨラリルレロワヲン".toCharArray()
    private val random = Random(0x4D4154524958L) // "MATRIX"
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        textSize = 15f * density
    }
    private val headPaint = Paint(textPaint).apply { color = Color.rgb(210, 255, 220) }
    private val fadePaint = Paint().apply { color = Color.argb(52, 0, 0, 0) }

    private var columns: Array<Column> = emptyArray()
    private var lineHeight = textPaint.textSize * 1.12f
    private var accent = Color.rgb(0, 255, 65)
    private var animating = false
    private var staticFrame = false
    private var windowVisible = true
    private var lastFrameAt = 0L
    private var elapsedMs = 0f

    private val ticker = object : Runnable {
        override fun run() {
            if (!animating) return
            val now = SystemClock.uptimeMillis()
            if (lastFrameAt != 0L && now - lastFrameAt < FRAME_MS) {
                postOnAnimation(this)
                return
            }
            val delta = if (lastFrameAt == 0L) 0f else (now - lastFrameAt).coerceAtMost(100L) / 1000f
            lastFrameAt = now
            elapsedMs += delta
            step(delta)
            invalidate()
            // Именно ticker, а не postInvalidateOnAnimation: последний только
            // просит перерисовать кадр и не возвращается в этот Runnable.
            postOnAnimation(this)
        }
    }

    init {
        setWillNotDraw(false)
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /** Тема может передать свой акцент; по умолчанию — люминофорный зелёный. */
    fun applyAccent(color: Int) {
        if (accent == color) return
        accent = color
        invalidate()
    }

    fun startRain() {
        if (animating) return
        animating = true
        staticFrame = !animationAllowed()
        lastFrameAt = 0L
        removeCallbacks(ticker)
        if (staticFrame) {
            invalidate()
            return
        }
        postOnAnimation(ticker)
    }

    fun stopRain() {
        animating = false
        removeCallbacks(ticker)
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        windowVisible = windowVisibility == VISIBLE
    }

    override fun onDetachedFromWindow() {
        stopRain()
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        windowVisible = visibility == VISIBLE
        if (!windowVisible) {
            removeCallbacks(ticker)
        } else if (animating && !staticFrame) {
            removeCallbacks(ticker)
            lastFrameAt = 0L
            postOnAnimation(ticker)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        lineHeight = textPaint.textSize * 1.12f
        val columnWidth = max(1, (textPaint.textSize * 1.18f).toInt())
        val count = max(1, w / columnWidth)
        columns = Array(count) { index ->
            Column(
                headY = if (h > 0) random.nextFloat() * h else 0f,
                speed = (10f + random.nextFloat() * 26f) * density,
                trail = 7 + random.nextInt(12),
                glyphOffset = random.nextInt(glyphs.size) + index,
            )
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!isShown || columns.isEmpty()) return
        val width = width.toFloat()
        val height = height.toFloat()
        if (width <= 0f || height <= 0f) return

        // Fade старого кадра оставляет следы; статичный режим рисует один раз
        // поверх прозрачного фона, поэтому ему fade не нужен.
        if (!staticFrame) canvas.drawRect(0f, 0f, width, height, fadePaint)

        val columnWidth = max(1f, textPaint.textSize * 1.18f)
        for (index in columns.indices) {
            val column = columns[index]
            val x = index * columnWidth
            if (x > width) continue
            val headY = column.headY
            for (step in 0 until column.trail) {
                val y = headY - step * lineHeight
                if (y < -lineHeight || y > height + lineHeight) continue
                val glyph = glyphs[(column.glyphOffset + step + (elapsedMs / 90).toInt()) % glyphs.size]
                val fade = 1f - step.toFloat() / column.trail.toFloat()
                val paint = if (step == 0) headPaint else textPaint
                if (step != 0) {
                    paint.color = Color.argb(
                        (35 + 220 * fade * fade).toInt().coerceIn(0, 255),
                        Color.red(accent),
                        Color.green(accent),
                        Color.blue(accent),
                    )
                }
                canvas.drawText(glyph.toString(), x, y, paint)
            }
        }
    }

    private fun step(deltaSeconds: Float) {
        if (staticFrame || columns.isEmpty()) return
        val height = height.toFloat()
        for (column in columns) {
            column.headY += column.speed * deltaSeconds
            if (column.headY - column.trail * lineHeight > height + lineHeight) {
                column.headY = -random.nextFloat() * lineHeight * 4f
                column.speed = (10f + random.nextFloat() * 26f) * density
                column.trail = 7 + random.nextInt(12)
                column.glyphOffset = random.nextInt(glyphs.size)
            }
        }
    }

    private fun animationAllowed(): Boolean {
        if (!MainBackgroundPolicy.isAnimationSupported(context)) return false
        return try {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            ) > 0f
        } catch (_: Throwable) {
            true
        }
    }

    private companion object {
        const val FRAME_MS = 33L
    }
}
