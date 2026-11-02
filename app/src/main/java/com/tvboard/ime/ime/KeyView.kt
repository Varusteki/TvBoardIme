package com.tvboard.ime.ime

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import androidx.core.content.ContextCompat
import com.tvboard.ime.R
import com.tvboard.ime.util.dp
import kotlin.math.min

/**
 * 单个按键。
 *
 * 不用 TextView + selector：TV 上焦点重绘频繁，自绘一次 roundRect 比走一遍
 * drawable 状态机轻得多，也避免 API 26 才有的 defaultFocusHighlight 差异。
 */
class KeyView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private companion object {
        /** 按下时缩到 93%：肉眼可见但不至于像"键变小了" */
        const val PRESS_SCALE = 0.93f
        const val PRESS_IN_MS = 70L
        const val PRESS_OUT_MS = 110L
    }

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT
    }
    private val markPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** 矢量图标专用：描边 + 圆头圆角，形态与 ← → 的笔画风格一致 */
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val iconPath = Path()
    private val rect = RectF()
    private val markRect = RectF()

    /** 复用同一个 Rect 量字形墨迹，避免 onDraw 里每帧 new 对象 */
    private val glyph = Rect()

    private val colorKey = ContextCompat.getColor(context, R.color.bg_key)
    private val colorKeyFunc = ContextCompat.getColor(context, R.color.bg_key_func)
    private val colorFocus = ContextCompat.getColor(context, R.color.focus)
    private val colorShiftArmed = ContextCompat.getColor(context, R.color.shift_armed)
    private val colorShiftLocked = ContextCompat.getColor(context, R.color.shift_locked)
    private val colorText = ContextCompat.getColor(context, R.color.text_primary)
    private val colorTextOnFocus = ContextCompat.getColor(context, R.color.text_on_focus)

    private var label: String = ""

    /**
     * ⇧ 键的三态：0 = 关闭，1 = 待发一次（下一个字符大写），2 = 大写锁定。
     *
     * 三态必须"一眼可分"，只靠 ⇧/⇪ 两个字形在三米外根本看不出差别，所以改成
     * 底色 + 字色 + 锁定标记三重区分。非 ⇧ 键恒为 0。
     */
    private var shiftState: Int = 0

    var keyDef: KeyDef? = null
        private set

    /** 触摸点击回调。由 KeyboardView 绑定，和遥控器确认键走同一套语义 */
    var onTap: (() -> Unit)? = null

    private var lastPressed = false

    /** 手指是否还按在键帽内。滑出去再抬手不算点击，和系统按钮行为一致 */
    private var touchActive = false

    private val releaseRunnable = Runnable {
        isPressed = false
    }

    init {
        isFocusable = true
        /*
         * 必须为 true。默认 false 时，触摸模式（touch mode）下 requestFocus() 会
         * 直接返回 false —— 这是遥控器方向键"完全没反应"的根因：
         * 模拟器/触屏设备点一下就进入 touch mode，此后 FocusNavigator 找到的
         * 目标视图永远拿不到焦点，界面上看不到任何高亮变化。
         */
        isFocusableInTouchMode = true
        isClickable = true
        setOnClickListener {
            // 触摸也要把焦点带过来，保证"鼠标点过的键"与"遥控器选中的键"是同一个
            requestFocus()
            onTap?.invoke()
        }
    }

    fun bind(def: KeyDef) {
        keyDef = def
        label = def.label
        contentDescription = def.label
        // 视图可能被复用到另一次布局，动画与按下态都归零
        resetPress()
        invalidate()
    }

    /**
     * 遥控器确认键的按下反馈。
     *
     * 直接借 `isPressed`：pressed 状态变化会走 [drawableStateChanged]，
     * 于是触摸与遥控器共用同一套底色 + 缩放动画，不会出现两种观感。
     */
    fun flashPress(durationMs: Long = 90L) {
        removeCallbacks(releaseRunnable)
        isPressed = true
        postDelayed(releaseRunnable, durationMs)
    }

    private fun resetPress() {
        removeCallbacks(releaseRunnable)
        if (isPressed) isPressed = false
        animate().cancel()
        scaleX = 1f
        scaleY = 1f
        lastPressed = false
        touchActive = false
    }

    /**
     * 触摸处理：自己接管，不走 `View.onTouchEvent` 的点击链路。
     *
     * 框架的 `onTouchEvent` 在 ACTION_UP 里有这么一段（AOSP View.java）：
     *
     * ```
     * if (isFocusable() && isFocusableInTouchMode() && !isFocused()) {
     *     focusTaken = requestFocus();
     * }
     * if (!focusTaken) { performClick(); }
     * ```
     *
     * 也就是说：**一个可焦点但当前没焦点的键，第一次点下去只做 `requestFocus()`，
     * 点击被直接跳过**，必须再点一次（此时已聚焦，`focusTaken` 为 false）才真正触发。
     * 这就是"要点两下"的根因，与我们的监听器无关。
     *
     * 而 `isFocusableInTouchMode` 又不能关掉：touch mode 下它是 `requestFocus()` 的
     * 前置条件，关掉之后遥控器方向键就彻底没反应。
     *
     * 折中办法是自己处理触摸：按下就抢焦点、置 pressed（动画与底色由此触发），
     * 抬起时直接 [performClick]，绕开上面那次"聚焦即吞点击"的分支。
     * 按键只有几十个像素大，不需要系统的滑动取消判定，简单判一下是否还在键内即可。
     */
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchActive = true
                // 按下即抢焦点：与遥控器"焦点即高亮"的语义对齐，不留两个高亮
                requestFocus()
                isPressed = true
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (touchActive) {
                    val inside = event.x >= 0f && event.x <= width &&
                        event.y >= 0f && event.y <= height
                    if (isPressed != inside) isPressed = inside
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                val fire = touchActive && isPressed
                touchActive = false
                isPressed = false
                // 先复位再做动作：handleAction 可能触发切页重建，此刻不该还留着按下态
                if (fire) performClick()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                touchActive = false
                isPressed = false
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(releaseRunnable)
        animate().cancel()
        super.onDetachedFromWindow()
    }

    /**
     * 按下动效。
     *
     * 按下 0.93 倍（70ms，减速），回弹带一点过冲（110ms），形成"被按下去又弹回来"
     * 的手感。只做 scale：不触发父容器重新布局，十个键同时按也不会掉帧。
     */
    private fun animatePress(down: Boolean) {
        animate().cancel()
        if (down) {
            animate()
                .scaleX(PRESS_SCALE).scaleY(PRESS_SCALE)
                .setDuration(PRESS_IN_MS)
                .setInterpolator(DecelerateInterpolator())
                .start()
        } else {
            animate()
                .scaleX(1f).scaleY(1f)
                .setDuration(PRESS_OUT_MS)
                .setInterpolator(OvershootInterpolator(1.8f))
                .start()
        }
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        val pressed = isPressed
        if (pressed == lastPressed) return
        lastPressed = pressed
        animatePress(pressed)
        // 按下态影响底色（见 onDraw），状态一动就必须重绘
        invalidate()
    }

    fun setLabel(text: String) {
        if (label == text) return
        label = text
        // 大小写联动后无障碍播报也要跟着变
        contentDescription = text
        invalidate()
    }

    /** 仅 ⇧ 键使用；state 取 0/1/2，见 [shiftState] */
    fun setShiftState(state: Int) {
        if (shiftState == state) return
        shiftState = state
        invalidate()
    }

    override fun onFocusChanged(
        gainFocus: Boolean,
        direction: Int,
        previouslyFocusedRect: Rect?
    ) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        invalidate()
    }

    /**
     * 收起键盘的图标：一个向下折角（`⌄` 的矢量版）。
     *
     * 尺寸按键的真实宽高现场算，不用固定 dp：底部行各键权重不同（语言键 1.3、空格 3.0、
     * 收起键 0.9），固定值会在窄键上顶边、在宽键上显得空。宽高比固定 2:1，笔画粗细
     * 跟着高度走，保证任何键型上都和 ← → 的笔画观感一致。
     */
    private fun drawHideIcon(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        // 两个上限取小：键窄时不横向溢出，键矮时不超过字母的视觉高度
        val iconW = min(width * 0.34f, height * 0.46f)
        val iconH = iconW * 0.5f

        iconPaint.color = textPaint.color
        iconPaint.strokeWidth = (iconH * 0.26f).coerceAtLeast(dp(2f).toFloat())

        iconPath.reset()
        iconPath.moveTo(cx - iconW / 2f, cy - iconH / 2f)
        iconPath.lineTo(cx, cy + iconH / 2f)
        iconPath.lineTo(cx + iconW / 2f, cy - iconH / 2f)
        canvas.drawPath(iconPath, iconPaint)
    }

    override fun onDraw(canvas: Canvas) {
        val def = keyDef ?: return
        val inset = dp(2f).toFloat()
        rect.set(inset, inset, width - inset, height - inset)
        val radius = min(width, height) * 0.18f

        val armed = shiftState == 1
        val locked = shiftState == 2

        bgPaint.color = when {
            // 触摸按下与焦点高亮用同一视觉，避免"点了但没反应"的错觉
            isFocused || isPressed -> colorFocus
            locked -> colorShiftLocked
            armed -> colorShiftArmed
            def.functional -> colorKeyFunc
            else -> colorKey
        }
        canvas.drawRoundRect(rect, radius, radius, bgPaint)

        textPaint.color = when {
            isFocused || isPressed -> colorTextOnFocus
            locked -> colorTextOnFocus
            armed -> colorFocus
            else -> colorText
        }

        // 图标键到此为止：下面全是文字排版逻辑
        if (def.icon == KeyIcon.HIDE) {
            drawHideIcon(canvas)
            return
        }

        val chars = if (label.isEmpty()) 1 else label.length
        /*
         * 0.38 → 0.50：1080p / density 320 下一行键高约 79px，0.38 只给出 30px 的
         * em（字面可见高度约 21px），三米外看着明显偏小。0.50 得到约 39px em、
         * 字面高约 28px，仍留出上下各 25px 的空隙，不会顶到圆角。
         * 宽度约束继续按 1.5 倍均分，多字标签（空格 / Abc）仍由高度决定。
         */
        val base = min(height * 0.50f, width * 1.5f / chars)
            .coerceAtLeast(dp(10f).toFloat())
        val byInk = def.inkRatio > 0f && label.isNotEmpty()

        if (byInk) {
            /*
             * 符号键：先量一遍墨迹高度，再按目标高度反推字号。
             *
             * 之前是"字号 × 固定倍率"，但箭头的墨迹只有同字号字母的六成高、回车
             * 接近全高，同一个倍率下必然一个偏小一个偏大。直接以墨迹为基准，
             * 一批符号才能对齐到同一视觉高度。
             */
            textPaint.textSize = base
            textPaint.getTextBounds(label, 0, label.length, glyph)
            val inkH = glyph.height()
            if (inkH > 0) {
                textPaint.textSize = base * (height * def.inkRatio) / inkH
            }
            // 宽度兜底：窄键（←/→ 权重只有 1.1）上宁可少压一点高度，也不能左右溢出
            textPaint.getTextBounds(label, 0, label.length, glyph)
            val maxW = width * 0.84f
            if (glyph.width() > maxW) {
                textPaint.textSize *= maxW / glyph.width()
            }
            textPaint.getTextBounds(label, 0, label.length, glyph)
        } else {
            textPaint.textSize = base
            textPaint.getTextBounds(label, 0, label.length, glyph)
        }

        // 锁定态下方要留出横杠的位置，字重心整体上移一点
        val centerY = if (locked) height / 2f - dp(3f) else height / 2f
        /*
         * 垂直居中分两套：
         * - 符号按墨迹中心对齐。字体行高中心与箭头墨迹中心并不重合（箭头画在中线偏上，
         *   但 ⇧ 这类符号又偏高），按行高居中就会整体偏沉，正是"看着没居中"的来源。
         * - 普通文字仍按行高居中，保证 qwerty 各行内 p / a 这种有降部无降部的字母基线一致。
         */
        val baseline = if (byInk) {
            centerY - (glyph.top + glyph.bottom) / 2f
        } else {
            centerY - (textPaint.descent() + textPaint.ascent()) / 2f
        }
        canvas.drawText(label, width / 2f, baseline, textPaint)

        if (locked) {
            // 实心底色 + 深色字 + 底部横杠：焦点在别的键上时也能一眼看出「锁住了」
            markPaint.color = textPaint.color
            val barW = width * 0.34f
            val barH = dp(2.5f).toFloat()
            // 0.78 而不是 0.75：⇧ 放大 1.28 倍后字底更低，0.75 会贴到横杠
            val barTop = height * 0.78f
            markRect.set(
                width / 2f - barW / 2f,
                barTop,
                width / 2f + barW / 2f,
                barTop + barH
            )
            canvas.drawRoundRect(markRect, barH / 2f, barH / 2f, markPaint)
        }
    }
}
