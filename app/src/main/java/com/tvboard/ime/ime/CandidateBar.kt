package com.tvboard.ime.ime

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import com.tvboard.ime.R
import com.tvboard.ime.util.dp

/**
 * 候选栏。
 *
 * 刻意内嵌在键盘视图内（而非 onCreateCandidatesView）：CandidatesView 位于 IME 窗口的
 * 独立区域，TV 上 D-pad 无法把焦点移进去，用户永远选不到词（设计文档 D3）。
 *
 * 整条栏是一个可聚焦 View，内部用 ←/→ 改变选中项，避免为每个候选项建视图。
 */
class CandidateBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.LEFT
    }
    private val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 1f
    }
    private val rect = RectF()

    private val colorBg = ContextCompat.getColor(context, R.color.bg_panel)
    private val colorFocus = ContextCompat.getColor(context, R.color.focus)
    private val colorSelect = ContextCompat.getColor(context, R.color.bg_key)
    private val colorText = ContextCompat.getColor(context, R.color.text_primary)
    private val colorSecondary = ContextCompat.getColor(context, R.color.text_secondary)
    private val colorTextOnFocus = ContextCompat.getColor(context, R.color.text_on_focus)
    private val colorDivider = ContextCompat.getColor(context, R.color.divider)

    private var composing: String = ""
    private var items: List<String> = emptyList()
    private var selected: Int = 0

    /** 空白态提示文案。英文页没有候选词，靠它常驻标识"当前是英文输入" */
    private var hint: String = ""

    var onCommit: ((String) -> Unit)? = null

    /** 有没有真正的候选项。空态时不该让遥控器把焦点停在这条空栏上 */
    val hasContent: Boolean get() = items.isNotEmpty()

    /** dp 返回 Int，直接参与 Canvas 的浮点运算会触发类型不匹配 */
    private fun dpf(value: Float): Float = dp(value).toFloat()

    init {
        isFocusable = true
        /*
         * 与 KeyView 同理：touch mode 下 false 会让 requestFocus() 直接失败，
         * 遥控器就永远选不中候选词。候选栏是输入中文的必经环节，不能只支持 D-pad。
         */
        isFocusableInTouchMode = true
    }

    /** 命中测试：把触摸点换算成候选项下标 */
    private fun indexAt(touchX: Float): Int {
        if (items.isEmpty()) return -1
        textPaint.textSize = dpf(11f)
        var x = dpf(12f)
        if (composing.isNotEmpty()) {
            x += textPaint.measureText(composing) + dp(16f)
        }
        items.forEachIndexed { index, item ->
            val w = textPaint.measureText(item)
            if (touchX >= x - dpf(8f) && touchX <= x + w + dpf(8f)) return index
            x += w + dp(22f)
        }
        return -1
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            // 按下即选中，抬起才提交：和遥控器"移过去再按确认"的行为对齐
            MotionEvent.ACTION_DOWN -> {
                requestFocus()
                indexAt(event.x).takeIf { it >= 0 }?.let {
                    selected = it
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                val idx = indexAt(event.x)
                if (idx >= 0 && idx == selected) {
                    commitSelected()
                }
                return true
            }
        }
        return true
    }

    fun setData(newComposing: String, newItems: List<String>) {
        composing = newComposing
        items = newItems
        if (selected >= items.size) selected = 0
        invalidate()
    }

    fun setHint(text: String) {
        if (hint == text) return
        hint = text
        invalidate()
    }

    fun currentSelection(): String? = items.getOrNull(selected)

    fun moveSelection(delta: Int): Boolean {
        if (items.isEmpty()) return false
        val next = selected + delta
        if (next < 0 || next >= items.size) return false
        selected = next
        invalidate()
        return true
    }

    fun commitSelected(): Boolean {
        val value = currentSelection() ?: return false
        onCommit?.invoke(value)
        return true
    }

    override fun onFocusChanged(
        gainFocus: Boolean,
        direction: Int,
        previouslyFocusedRect: android.graphics.Rect?
    ) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val radius = dpf(6f)
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        bgPaint.color = colorBg
        canvas.drawRoundRect(rect, radius, radius, bgPaint)

        if (isFocused) {
            bgPaint.color = colorFocus
            bgPaint.style = Paint.Style.STROKE
            bgPaint.strokeWidth = dp(2f).toFloat()
            canvas.drawRoundRect(rect, radius, radius, bgPaint)
            bgPaint.style = Paint.Style.FILL
        }

        textPaint.textSize = dpf(11f)
        val baseline = height / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
        var x = dpf(12f)

        if (composing.isNotEmpty()) {
            textPaint.color = colorSecondary
            canvas.drawText(composing, x, baseline, textPaint)
            x += textPaint.measureText(composing) + dp(16f)
            dividerPaint.color = colorDivider
            canvas.drawLine(x - dpf(8f), dpf(5f), x - dpf(8f), height - dpf(5f), dividerPaint)
        }

        if (composing.isEmpty() && items.isEmpty() && hint.isNotEmpty()) {
            /*
             * 空态占位。整条栏在英文/符号页永远是空的，写一行灰字既填了这块空白，
             * 也让"现在是什么模式"有地方可看 —— 之前模式只能从键盘上那个
             * 「中 / Abc」键反推，得先找到它。
             */
            textPaint.color = colorSecondary
            canvas.drawText(hint, x, baseline, textPaint)
            return
        }

        items.forEachIndexed { index, item ->
            val widthOfItem = textPaint.measureText(item)
            if (index == selected && items.isNotEmpty()) {
                val hl = RectF(
                    x - dpf(8f),
                    dpf(2f),
                    x + widthOfItem + dpf(8f),
                    height - dpf(2f)
                )
                bgPaint.color = if (isFocused) colorFocus else colorSelect
                canvas.drawRoundRect(hl, dpf(5f), dpf(5f), bgPaint)
            }
            textPaint.color = if (index == selected && isFocused) colorTextOnFocus else colorText
            canvas.drawText(item, x, baseline, textPaint)
            x += widthOfItem + dp(22f)
        }
    }
}
