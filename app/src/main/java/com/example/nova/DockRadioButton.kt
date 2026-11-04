package com.example.nova

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatRadioButton
import androidx.core.graphics.drawable.DrawableCompat

/**
 * Кнопка канала в стиле macOS Dock.
 *
 * В покое — только SVG-иконка (или запасная точка). Палец/курсор подходит к
 * иконке: она подсвечивается и растёт, над ней проявляется подпись, а соседние
 * остаются в покое. Это принцип dock magnification из macOS/QML-примеров:
 * масштаб зависит от указателя, а не от факта нажатия.
 *
 * Вид рисует сам, не через `TextView`: стандартный RadioButton не умеет
 * одновременно быть иконкой и показывать подпись над собой без скачков layout.
 * Ширина/высота фиксированы стилем, поэтому раскладка строки не дёргается.
 */
class DockRadioButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = androidx.appcompat.R.attr.radioButtonStyle,
) : AppCompatRadioButton(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
        textSize = 9.5f * density
    }
    private val panelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private var accent = Color.rgb(0, 255, 65)
    private var dockIcon: Drawable? = null
    private var magnification = 0f
    private var dockTouchActive = false
    private var dockPreview = false
    private var previewProgress = 0f
    private var previewAnimator: android.animation.ValueAnimator? = null
    private var collapseAnimator: android.animation.ValueAnimator? = null

    init {
        setWillNotDraw(false)
        isClickable = true
        isFocusable = true
        background = null
        setButtonDrawable(null)
        val typed = context.obtainStyledAttributes(
            attrs,
            R.styleable.DockRadioButton,
            defStyleAttr,
            0,
        )
        dockIcon = typed.getDrawable(R.styleable.DockRadioButton_dockIcon)
        typed.recycle()
        dockIcon?.mutate()
    }

    fun setAccentColor(color: Int) {
        if (accent == color) return
        accent = color
        invalidate()
    }

    /** 0 — обычная точка, 1 — точка с раскрытой подписью. */
    fun setDockMagnification(value: Float) {
        val next = value.coerceIn(0f, 1f)
        collapseAnimator?.cancel()
        collapseAnimator = null
        if (kotlin.math.abs(next - magnification) < 0.01f) return
        magnification = next
        invalidate()
    }

    /**
     * Пока палец ведёт по ползунку, выбранной рисуется точка под пальцем, а
     * прежний выбор временно гаснет. На отпускании RadioGroup получит уже
     * окончательный выбор.
     */
    fun setDockTouchActive(active: Boolean) {
        if (dockTouchActive == active) return
        dockTouchActive = active
        if (!active && dockPreview) {
            dockPreview = false
            animatePreviewProgress(0f)
        }
        invalidate()
    }

    fun setDockPreview(preview: Boolean) {
        if (dockPreview == preview) return
        dockPreview = preview
        animatePreviewProgress(if (preview) 1f else 0f)
        invalidate()
    }

    /** Плавно подводит «выбранную» точку к размеру полной подписи и обратно. */
    private fun animatePreviewProgress(target: Float) {
        previewAnimator?.cancel()
        if (kotlin.math.abs(previewProgress - target) < 0.01f) {
            previewProgress = target
            invalidate()
            return
        }
        previewAnimator = android.animation.ValueAnimator.ofFloat(previewProgress, target).apply {
            duration = if (target > previewProgress) 170L else 150L
            interpolator = android.view.animation.DecelerateInterpolator()
            addUpdateListener {
                previewProgress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    /** Плавно возвращает точку в состояние покоя после отпускания. */
    fun collapseDock() {
        collapseAnimator?.cancel()
        if (magnification <= 0.01f) return
        collapseAnimator = android.animation.ValueAnimator.ofFloat(magnification, 0f).apply {
            duration = 130L
            interpolator = android.view.animation.DecelerateInterpolator()
            addUpdateListener {
                magnification = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        collapseAnimator?.cancel()
        collapseAnimator = null
        previewAnimator?.cancel()
        previewAnimator = null
        super.onDetachedFromWindow()
    }

    override fun setText(text: CharSequence?, type: BufferType?) {
        super.setText(text, type)
        contentDescription = text
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val centerX = w / 2f
        val dotY = h - 14f * density
        val drawMagnification = maxOf(magnification, previewProgress)
        // Иконка — самостоятельная SVG-картинка, а не текст: она растёт вместе
        // с preview, поэтому увеличение читается как в dock-примерах с картинками.
        val iconSize = (14f + 10f * drawMagnification) * density
        val iconHalf = iconSize / 2f
        val labelProgress = ((drawMagnification - 0.18f) / 0.82f).coerceIn(0f, 1f)
        val labelScale = 0.15f + 0.85f * labelProgress
        val fade = if (isEnabled) 1f else 0.45f
        // Источник истины — RadioGroup, а не локальный isChecked: при смене
        // выбора перерисовываются все точки, и ни одна соседняя не должна
        // остаться закрашенной от предыдущего состояния. Пока палец ведёт по
        // дорожке, вместо окончательного выбора показывается preview.
        val checked = (parent as? android.widget.RadioGroup)?.checkedRadioButtonId == id
        val selected = if (dockTouchActive) dockPreview else checked

        // Лёгкая стеклянная подложка появляется только у раздутой иконки, чтобы
        // в покое dock оставался набором иконок, а не рядом невидимых карточек.
        if (drawMagnification > 0.08f) {
            panelPaint.color = Color.argb(
                (18f + 34f * drawMagnification).toInt().coerceIn(0, 255),
                235, 248, 240,
            )
            canvas.drawRoundRect(
                1f * density, 2f * density, w - 1f * density, h - 1f * density,
                12f * density, 12f * density, panelPaint,
            )
        }

        // Выбранная иконка — акцент; остальные — приглушённые, с тонким кольцом.
        if (selected) {
            ringPaint.color = withAlpha(accent, (190f * fade).toInt())
            ringPaint.strokeWidth = 1.8f * density
            canvas.drawCircle(centerX, dotY, iconHalf + 3.5f * density, ringPaint)
        } else {
            ringPaint.color = withAlpha(Color.WHITE, (80f * fade).toInt())
            ringPaint.strokeWidth = 1f * density
            canvas.drawCircle(centerX, dotY, iconHalf + 2f * density, ringPaint)
        }

        val icon = dockIcon
        if (icon != null) {
            DrawableCompat.setTint(
                icon,
                withAlpha(
                    if (selected) accent else Color.rgb(190, 212, 200),
                    (255f * fade).toInt(),
                ),
            )
            icon.setBounds(
                (centerX - iconHalf).toInt(),
                (dotY - iconHalf).toInt(),
                (centerX + iconHalf).toInt(),
                (dotY + iconHalf).toInt(),
            )
            icon.draw(canvas)
        } else {
            // Запасной вид для вызова без dockIcon — прежняя точка.
            val dotRadius = (6.5f + 3.5f * drawMagnification) * density
            dotPaint.color = withAlpha(
                if (selected) accent else Color.rgb(220, 232, 225),
                ((if (selected) 255f else 120f) * fade).toInt(),
            )
            canvas.drawCircle(centerX, dotY, dotRadius, dotPaint)
        }

        if (labelProgress > 0f) {
            val label = text?.toString().orEmpty()
            if (label.isNotEmpty()) {
                labelPaint.color = withAlpha(Color.rgb(235, 248, 240), (255f * labelProgress * fade).toInt())
                val maxWidth = w - 2f * density
                val measured = labelPaint.measureText(label)
                labelPaint.textScaleX = if (measured > maxWidth && measured > 0f) {
                    (maxWidth / measured).coerceAtLeast(0.72f)
                } else {
                    1f
                }
                val baseline = (dotY - iconHalf - 5f * density).coerceAtLeast(labelPaint.textSize)
                canvas.save()
                canvas.translate(centerX, baseline)
                canvas.scale(labelScale, labelScale)
                canvas.drawText(label, 0f, 0f, labelPaint)
                canvas.restore()
                labelPaint.textScaleX = 1f
            }
        }
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))
}
