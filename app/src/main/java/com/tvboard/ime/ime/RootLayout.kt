package com.tvboard.ime.ime

import android.content.Context
import android.util.AttributeSet
import android.util.Log
import android.view.KeyEvent
import android.view.ViewGroup
import com.tvboard.ime.BuildConfig
import com.tvboard.ime.qr.QrPanel
import com.tvboard.ime.util.dp

/**
 * IME 根布局：左侧键盘区 + 右侧常驻二维码面板。
 *
 * 二维码面板挂在这里而不是挂进各键盘页 —— 切页/切语言/候选栏显隐都不重建它，
 * 否则每次切页都要重新编码一张位图（A53 上 30–80ms，肉眼可见卡顿）。
 */
class RootLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : ViewGroup(context, attrs) {

    interface Host {
        fun onRequestHide()
        fun onOpenSettings()
        fun onCursorMove(delta: Int)
    }

    companion object {
        /** IME 窗口占屏高度。让给二维码比让给键盘更有价值 */
        private const val HEIGHT_RATIO = 0.48f

        /** 面板宽度占屏宽比例，由可扫距离反推（设计文档 5.5） */
        private const val QR_WIDTH_RATIO = 0.24f

        private const val TAG = "TvBoardIme"

        /** 由本布局接管的按键，其余键照常交给系统 */
        private val NAV_KEYS = intArrayOf(
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_MENU
        )
    }

    val keyboard = KeyboardView(context)
    val qrPanel = QrPanel(context)

    var host: Host? = null

    /** 按键去重用：同一物理事件可能从两条链各到一次 */
    private var lastKeyCode = -1
    private var lastKeyTime = 0L

    init {
        addView(keyboard)
        addView(qrPanel)

        // 面板是纯展示区，必须彻底排除在焦点体系之外。
        // QrPanel 是纯 View（无子视图），isFocusable=false 已足够；
        // FocusNavigator 的候选集合也按 isFocusable 过滤了一层。
        qrPanel.isFocusable = false
        qrPanel.isFocusableInTouchMode = false

        // 面板不响应任何点击：既没有「点二维码放大」，也没有文件入口了。
        qrPanel.isClickable = false

        keyboard.onAction = { action ->
            when (action) {
                KeyboardView.Action.HIDE -> host?.onRequestHide()
                KeyboardView.Action.CURSOR_LEFT -> host?.onCursorMove(-1)
                KeyboardView.Action.CURSOR_RIGHT -> host?.onCursorMove(1)
            }
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec).coerceAtLeast(1)
        val limit = MeasureSpec.getSize(heightMeasureSpec)
        /*
         * 候选栏的高度不参与"分给按键行"的预算，而是让整个 IME 窗口长高同样的值。
         * 三页都显示候选栏后这个增量恒定，于是中英/符号之间切换时窗口高度、
         * 每行键高、右侧二维码位置全部保持不变 —— 布局不再"跳一下"。
         */
        val desired = (resources.displayMetrics.heightPixels * HEIGHT_RATIO).toInt() +
            keyboard.candidateBarExtraHeight()
        val height = if (limit in 1 until desired) limit else desired

        val qrWidth = (width * QR_WIDTH_RATIO).toInt()
        val gap = dp(4f)
        val keyboardWidth = (width - qrWidth - gap).coerceAtLeast(1)

        keyboard.measure(exactly(keyboardWidth), exactly(height))
        qrPanel.measure(exactly(qrWidth), exactly(height))
        setMeasuredDimension(width, height)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val gap = dp(4f)
        val qrWidth = qrPanel.measuredWidth
        val keyboardWidth = (width - qrWidth - gap).coerceAtLeast(1)
        keyboard.layout(0, 0, keyboardWidth, height)
        qrPanel.layout(keyboardWidth + gap, 0, width, height)
    }

    /**
     * 按键处理唯一入口。
     *
     * 两个通道都会调到这里：
     *   1. View 链：dispatchKeyEvent（窗口 decorView 正常分发）
     *   2. Window 回调链：InputMethodService.onKeyDown/onKeyUp（部分 ROM 走这条）
     * 用 (keyCode, eventTime) 去重，避免一次按键让焦点跳两格。
     *
     * 两条关键约束：
     * - 导航键的 ACTION_UP 必须吞掉。放回系统会让 ViewRootImpl 执行焦点导航，
     *   焦点走出 IME 窗口，键盘随之隐藏 —— 表现就是"按上键按几次键盘消失了"。
     * - 到边界时保持原地不动，不把事件交出去。
     */
    fun handleKeyEvent(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        if (!isNavKey(keyCode)) return false

        if (keyCode == lastKeyCode && event.eventTime == lastKeyTime) return true
        lastKeyCode = keyCode
        lastKeyTime = event.eventTime

        if (BuildConfig.DEBUG) {
            Log.d(
                TAG,
                "key code=$keyCode action=${event.action} repeat=${event.repeatCount} " +
                    "focus=${findFocus()?.javaClass?.simpleName}"
            )
        }

        // 抬起事件一律消费，理由见上面的注释
        if (event.action == KeyEvent.ACTION_UP) return true
        if (event.action != KeyEvent.ACTION_DOWN) return true

        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (keyboard.isCandidateBarFocused()) {
                    keyboard.moveCandidateSelection(-1)
                } else {
                    keyboard.moveFocus(FocusNavigator.Dir.LEFT)
                }
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (keyboard.isCandidateBarFocused()) {
                    keyboard.moveCandidateSelection(1)
                } else {
                    keyboard.moveFocus(FocusNavigator.Dir.RIGHT)
                }
            }
            KeyEvent.KEYCODE_DPAD_UP -> keyboard.moveFocus(FocusNavigator.Dir.UP)
            KeyEvent.KEYCODE_DPAD_DOWN -> keyboard.moveFocus(FocusNavigator.Dir.DOWN)
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (event.repeatCount == 0) keyboard.press() else keyboard.onRepeat(event)
            }
            KeyEvent.KEYCODE_BACK -> if (event.repeatCount == 0) host?.onRequestHide()
            KeyEvent.KEYCODE_MENU -> if (event.repeatCount == 0) host?.onOpenSettings()
        }
        return true
    }

    private fun isNavKey(keyCode: Int): Boolean = NAV_KEYS.contains(keyCode)

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (handleKeyEvent(event)) return true
        return super.dispatchKeyEvent(event)
    }

    private fun exactly(size: Int): Int =
        MeasureSpec.makeMeasureSpec(size.coerceAtLeast(1), MeasureSpec.EXACTLY)
}
