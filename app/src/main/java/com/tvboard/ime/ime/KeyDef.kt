package com.tvboard.ime.ime

/** 按键语义。与视图无关，便于单测与后续换成别的渲染方式。 */
sealed class KeyAction {

    data class Char(val text: String) : KeyAction()

    object Space : KeyAction()
    object Backspace : KeyAction()
    object Enter : KeyAction()
    object Shift : KeyAction()

    /** 中文 / 英文切换 */
    object SwitchLang : KeyAction()

    /** 字母 / 符号切换 */
    object SwitchLayout : KeyAction()

    object CursorLeft : KeyAction()
    object CursorRight : KeyAction()

    /** 收起键盘。等价于返回键，但语义明确、按一次就到 */
    object Hide : KeyAction()
}

/**
 * 用矢量直接画的按键图标。
 *
 * 为什么不用 Unicode 字符：`⌄ ⏷ ▽` 这类"收起"符号在不同系统字体里的有无、字形宽度、
 * 笔画粗细都不可控（缺字时直接显示豆腐块），而且无法像 [KeyDef.inkRatio] 那样
 * 精确约束视觉尺寸。图标改成代码绘制后，尺寸、线宽、居中全部可控，也不依赖字体。
 */
enum class KeyIcon {
    /** 不画图标，走文字 */
    NONE,

    /** 向下折角，表示"收起键盘" */
    HIDE
}

data class KeyDef(
    val label: String,
    val action: KeyAction,
    val weight: Float = 1f,
    val functional: Boolean = false,
    /**
     * 目标墨迹高度，占按键高度的比例；0 表示走普通文本逻辑（按字号排版）。
     *
     * 为什么不用「字号乘一个倍率」：`← → ↵ ⇧` 这类符号的字形边界框差异极大
     * （箭头只有同字号字母的六成高，回车则接近全高），固定倍率必然有的偏大有的偏小。
     * 这里直接声明"我希望这个字的墨迹有多高"，由 [KeyView] 量出实际边界框反推字号，
     * 一批符号的视觉大小就能对齐到同一条线上。
     */
    val inkRatio: Float = 0f,

    /**
     * 图标。非 [KeyIcon.NONE] 时忽略 [label] 的字形，改画矢量图标；
     * [label] 仍保留为无障碍播报文案。
     */
    val icon: KeyIcon = KeyIcon.NONE
)

data class KeyRow(val keys: List<KeyDef>)

data class KeyLayout(
    val id: String,
    val rows: List<KeyRow>,
    /**
     * 是否显示候选栏。
     *
     * **所有页都应为 true**。候选栏占一行高度，若只在中英某一态出现，IME 窗口、
     * 按键行高、右侧二维码位置都会跟着切换跳动。恒常显示后布局完全稳定，
     * 没有候选词时这条栏用来放提示文案（见 [hint]）。
     */
    val showCandidates: Boolean = false,

    /** 候选栏为空白时显示的提示文案，用于标识当前输入模式 */
    val hint: String = ""
)
