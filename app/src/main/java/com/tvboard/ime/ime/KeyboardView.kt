package com.tvboard.ime.ime

import android.content.Context
import android.text.InputType
import android.util.AttributeSet
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import com.tvboard.ime.BuildConfig
import com.tvboard.ime.util.dp

/**
 * 键盘视图。
 *
 * 只负责"布局 + 状态 + 上屏"，焦点寻址交给 FocusNavigator，写入交给 TextInjector。
 * 切页时重建 KeyView（数量 ≤ 47，重建成本远低于维护增量更新）。
 */
class KeyboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : ViewGroup(context, attrs) {

    companion object {
        private const val TAG = "TvBoardIme"

        /** 候选栏高度、以及它与按键区之间的间距 */
        private const val BAR_HEIGHT_DP = 30f
        private const val BAR_GAP_DP = 4f
    }

    enum class Action { HIDE, CURSOR_LEFT, CURSOR_RIGHT }

    var onCommit: ((String) -> Unit)? = null
    var onBackspace: (() -> Unit)? = null
    var onAction: ((Action) -> Unit)? = null

    private val candidateBar = CandidateBar(context)
    private val engine by lazy { InputEngine(context) }

    private var rowViews: List<List<KeyView>> = emptyList()
    private var layout: KeyLayout = LayoutProvider.letters(false)
    private var zh = false
    private var shift = false
    private var caps = false
    private var composing = ""
    private var shiftKey: KeyView? = null

    /** 焦点记忆：同一页内切页后回到上次停留的键 */
    private val focusMemory = HashMap<String, Int>()

    /** 上次打印过的高度，避免 onMeasure 每次都被刷屏 */
    private var lastLoggedHeight = -1

    init {
        candidateBar.visibility = GONE
        candidateBar.onCommit = { word -> commitCandidate(word) }
        addView(candidateBar)
        applyLayout(layout)
    }

    // ---------------------------------------------------------------- 公共接口

    fun applyEditorInfo(info: EditorInfo) {
        composing = ""
        candidateBar.setData("", emptyList())
        val inputClass = info.inputType and InputType.TYPE_MASK_CLASS
        // 数字/电话输入仍用字母页（顶部已有数字行），不额外做专用页
        if (inputClass == InputType.TYPE_CLASS_TEXT &&
            layout.id == LayoutProvider.ID_SYMBOLS
        ) {
            applyLayout(LayoutProvider.letters(zh))
        } else {
            post { restoreFocus() }
        }
    }

    /**
     * 按方向移动焦点，返回**焦点是否真的换了人**（到边界时为 false）。
     *
     * 键盘是焦点体系的边界：返回 false 就是"到头了，别再往外走"，
     * RootLayout 据此保持原地，不把事件放给 ViewRootImpl（放出去焦点会跑出 IME 窗口）。
     */
    fun moveFocus(dir: FocusNavigator.Dir): Boolean {
        val all = focusableViews()
        if (all.isEmpty()) return false

        val current = findFocus()
        // 焦点不在键盘内（刚进 IME、被系统抢走，或触摸模式下上次 requestFocus 失败）：
        // 先把焦点收进键盘。否则方向键看起来"完全没反应"，用户会以为键盘坏了。
        if (current == null || all.none { it === current }) {
            val first = all.firstOrNull()
            first?.requestFocus()
            log("moveFocus($dir) 焦点不在键盘内 → 收回到 ${first.desc()}")
            return true
        }

        val next = FocusNavigator.next(current, all, dir)
        if (next != null && next !== current) {
            val ok = next.requestFocus()
            log("moveFocus($dir) ${current.desc()} → ${next.desc()} ok=$ok")
            return ok
        }
        log("moveFocus($dir) 已到边界，保持 ${current.desc()}")
        return false
    }

    /** 把焦点拉回键盘（供服务在窗口重新获得焦点时调用） */
    fun ensureFocus() {
        val focused = findFocus()
        if (focused is KeyView) return
        if (focused === candidateBar && candidateBar.hasContent) return
        post { restoreFocus() }
    }

    fun press() {
        val focused = findFocus()
        if (focused === candidateBar) {
            candidateBar.commitSelected()
            return
        }
        val key = focused as? KeyView ?: return
        val def = key.keyDef ?: return
        // 遥控器没有触摸，这里补一次按下动效；触摸走 View 自己的 pressed 状态
        key.flashPress()
        handleAction(def)
    }

    fun onRepeat(event: KeyEvent) {
        val key = findFocus() as? KeyView ?: return
        val def = key.keyDef ?: return
        if (def.action === KeyAction.Backspace && event.eventTime - event.downTime >= 300L) {
            onBackspace?.invoke()
        }
    }

    fun onRelease() {
        // 当前无长按态需要清理；保留钩子以便后续接入"长按输入变体"
    }

    /** 候选栏内选择移动，返回是否发生了移动 */
    fun moveCandidateSelection(delta: Int): Boolean = candidateBar.moveSelection(delta)

    fun isCandidateBarFocused(): Boolean = findFocus() === candidateBar

    /**
     * 候选栏占用的高度（含与按键区的间距）。
     *
     * [RootLayout] 用它把 IME 窗口撑高同样的值。候选栏原本是从按键行的高度里扣出来的，
     * 每行要少掉十几像素。现在三页（英/中/符号）都显示这条栏、返回值恒定，窗口高度
     * 与行高都不再随切页变化，右侧二维码的位置也就固定了。
     */
    fun candidateBarExtraHeight(): Int =
        if (layout.showCandidates) dp(BAR_HEIGHT_DP) + dp(BAR_GAP_DP) else 0

    // ---------------------------------------------------------------- 布局

    private fun applyLayout(next: KeyLayout) {
        rememberFocus()
        removeAllViews()
        layout = next

        rowViews = next.rows.map { row ->
            row.keys.map { def ->
                KeyView(context).also { view ->
                    view.bind(def)
                    // 触摸走和遥控器确认键完全相同的语义。post 一层是因为
                    // 切页/切语言会在回调里 removeAllViews()，不能在 touch 分发
                    // 中途把正在处理的子视图从树里摘掉。
                    view.onTap = { post { handleAction(def) } }
                    addView(view)
                }
            }
        }

        shiftKey = rowViews.flatten().firstOrNull { it.keyDef?.action === KeyAction.Shift }
        updateShiftState()

        if (next.showCandidates) {
            if (candidateBar.parent == null) addView(candidateBar)
            candidateBar.visibility = VISIBLE
            candidateBar.setHint(next.hint)
            candidateBar.setData(composing, emptyList())
        } else {
            if (candidateBar.parent != null) removeView(candidateBar)
            candidateBar.visibility = GONE
        }

        requestLayout()
        invalidate()
        post { restoreFocus() }
    }

    private fun rememberFocus() {
        val focused = findFocus() as? KeyView ?: return
        val index = rowViews.flatten().indexOf(focused)
        if (index >= 0) focusMemory[layout.id] = index
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // 视图真正挂上窗口后才谈得上 requestFocus；applyLayout 的 post 有可能
        // 早于 attach（首次 inflate 时），这里补一次
        post { restoreFocus() }
    }

    private fun log(msg: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, msg)
    }

    private fun View?.desc(): String = when (this) {
        null -> "null"
        is KeyView -> "Key(${keyDef?.label})"
        is CandidateBar -> "CandidateBar"
        else -> javaClass.simpleName
    }

    private fun restoreFocus() {
        // 候选栏只有真出词时才该占着焦点；空态（英文页、刚提交完）必须让回键盘，
        // 否则遥控器会停在那条灰字提示上，且方向键要按两下才动得起来
        if (isCandidateBarFocused() && candidateBar.hasContent) return
        val flat = rowViews.flatten()
        if (flat.isEmpty()) return
        val remembered = focusMemory[layout.id]
        val target = (remembered?.let { flat.getOrNull(it) })
            ?: flat.getOrNull(flat.size / 2)
            ?: flat[0]
        target.requestFocus()
    }

    private fun focusableViews(): List<View> {
        val list = ArrayList<View>()
        rowViews.forEach { row ->
            row.forEach { if (it.visibility == VISIBLE) list.add(it) }
        }
        // 空候选栏不参与焦点寻址：它没有可选内容，落进去只会有个高亮框却没有作用
        if (layout.showCandidates && candidateBar.visibility == VISIBLE && candidateBar.hasContent) {
            list.add(candidateBar)
        }
        return list
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec).coerceAtLeast(1)
        val height = MeasureSpec.getSize(heightMeasureSpec).coerceAtLeast(1)

        val pad = dp(6f)
        val gap = dp(4f)
        val barHeight = if (layout.showCandidates) dp(BAR_HEIGHT_DP) else 0
        val reserved = pad * 2 + barHeight + (if (barHeight > 0) dp(BAR_GAP_DP) else 0)
        val contentWidth = (width - pad * 2).coerceAtLeast(1)
        val rowCount = rowViews.size
        val rowHeight = if (rowCount > 0) {
            ((height - reserved - gap * (rowCount - 1)) / rowCount).coerceAtLeast(dp(18f))
        } else {
            0
        }

        if (layout.showCandidates) {
            candidateBar.measure(
                MeasureSpec.makeMeasureSpec(contentWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(barHeight, MeasureSpec.EXACTLY)
            )
        }

        rowViews.forEach { row ->
            var totalWeight = 0f
            row.forEach { totalWeight += it.keyDef?.weight ?: 1f }
            if (totalWeight <= 0f) totalWeight = 1f
            val available = contentWidth - gap * (row.size - 1)
            row.forEach { key ->
                val weight = key.keyDef?.weight ?: 1f
                val keyWidth = (available * weight / totalWeight).toInt().coerceAtLeast(1)
                key.measure(
                    MeasureSpec.makeMeasureSpec(keyWidth, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(rowHeight, MeasureSpec.EXACTLY)
                )
            }
        }

        // 中英两页行高是否相等，光看截图不好判断，打一行日志便于回归比对
        if (BuildConfig.DEBUG && lastLoggedHeight != height) {
            lastLoggedHeight = height
            log("measure h=$height bar=$barHeight rowH=$rowHeight layout=${layout.id}")
        }

        setMeasuredDimension(width, height)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val pad = dp(6f)
        val gap = dp(4f)
        val barHeight = if (layout.showCandidates) dp(BAR_HEIGHT_DP) else 0
        var y = pad

        if (layout.showCandidates && candidateBar.parent != null) {
            candidateBar.layout(pad, y, pad + candidateBar.measuredWidth, y + barHeight)
            y += barHeight + dp(BAR_GAP_DP)
        }

        rowViews.forEach { row ->
            var x = pad
            var rowHeight = 0
            row.forEach { key ->
                val keyWidth = key.measuredWidth
                val keyHeight = key.measuredHeight
                key.layout(x, y, x + keyWidth, y + keyHeight)
                x += keyWidth + gap
                if (keyHeight > rowHeight) rowHeight = keyHeight
            }
            y += rowHeight + gap
        }
    }

    // ---------------------------------------------------------------- 按键语义

    private fun handleAction(def: KeyDef) {
        when (val action = def.action) {
            is KeyAction.Char -> inputChar(action.text)
            KeyAction.Space -> inputChar(" ")
            KeyAction.Backspace -> deleteBack()
            KeyAction.Enter -> {
                flushComposing()
                onCommit?.invoke("\n")
            }
            KeyAction.Shift -> toggleShift()
            KeyAction.SwitchLang -> switchLang()
            KeyAction.SwitchLayout -> switchLayout()
            KeyAction.CursorLeft -> onAction?.invoke(Action.CURSOR_LEFT)
            KeyAction.CursorRight -> onAction?.invoke(Action.CURSOR_RIGHT)
            KeyAction.Hide -> onAction?.invoke(Action.HIDE)
        }
    }

    private fun inputChar(raw: String) {
        if (zh && raw.length == 1 && raw[0] in 'a'..'z') {
            composing += raw
            refreshCandidates()
            return
        }
        flushComposing()
        onCommit?.invoke(applyCase(raw))
        if (shift && !caps) {
            shift = false
            updateShiftState()
        }
    }

    private fun applyCase(raw: String): String {
        if (!shift && !caps) return raw
        if (raw.length != 1) return raw
        val ch = raw[0]
        return if (ch in 'a'..'z') ch.uppercase() else raw
    }

    private fun deleteBack() {
        if (zh && composing.isNotEmpty()) {
            composing = composing.substring(0, composing.length - 1)
            refreshCandidates()
            return
        }
        onBackspace?.invoke()
    }

    private fun refreshCandidates() {
        if (!layout.showCandidates) return
        val list = if (composing.isEmpty()) emptyList() else engine.query(composing)
        candidateBar.setData(composing, list)
        // 候选词清空（提交 / 删完）后不能把焦点留在空栏上
        if (list.isEmpty() && isCandidateBarFocused()) post { restoreFocus() }
    }

    private fun commitCandidate(word: String) {
        onCommit?.invoke(word)
        composing = ""
        refreshCandidates()
        post { restoreFocus() }
    }

    private fun flushComposing() {
        if (composing.isEmpty()) return
        val best = candidateBar.currentSelection() ?: composing
        onCommit?.invoke(best)
        composing = ""
        refreshCandidates()
    }

    private fun toggleShift() {
        when {
            caps -> {
                caps = false
                shift = false
            }
            shift -> caps = true
            else -> shift = true
        }
        updateShiftState()
    }

    /**
     * 同步 ⇧ 键的三态样式与字母键的显示大小写。
     *
     * 上屏的大小写由 [applyCase] 决定，但视图上的字符必须同步换形，否则
     * 用户按了 ⇧/⇪ 只看到功能键图标变了、字母键还是小写，会以为没生效。
     * 中文页是拼音输入，字母键始终小写，不跟随 ⇧。
     *
     * ⇧ 键本身的三态（关闭 / 待发一次 / 大写锁定）交给 KeyView 用
     * 底色 + 字色 + 底部横杠区分，只换字形在三米外看不出来。
     */
    private fun updateShiftState() {
        val state = when {
            caps -> 2
            shift -> 1
            else -> 0
        }
        shiftKey?.setLabel(if (caps) "⇪" else "⇧")
        shiftKey?.setShiftState(state)

        val upper = !zh && (shift || caps)
        for (row in rowViews) {
            for (view in row) {
                val def = view.keyDef ?: continue
                if (def.action !is KeyAction.Char) continue
                // 始终从 KeyDef 的原始 label 推导，避免多次切换后累积错误
                view.setLabel(if (upper) def.label.uppercase() else def.label)
            }
        }
    }

    private fun switchLang() {
        zh = !zh
        composing = ""
        applyLayout(LayoutProvider.letters(zh))
    }

    private fun switchLayout() {
        val next = if (layout.id == LayoutProvider.ID_SYMBOLS) {
            LayoutProvider.letters(zh)
        } else {
            LayoutProvider.symbols(zh)
        }
        composing = ""
        applyLayout(next)
    }
}
