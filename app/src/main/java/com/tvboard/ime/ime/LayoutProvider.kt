package com.tvboard.ime.ime

/**
 * 布局定义。
 *
 * 每行 10 键：1080p 下单键约 96×72dp，遥控器 3–5 次按键即可到达任意字符。
 * 底部功能行位置固定，形成肌肉记忆。
 */
object LayoutProvider {

    const val ID_LETTERS_EN = "letters_en"
    const val ID_LETTERS_ZH = "letters_zh"
    const val ID_SYMBOLS = "symbols"

    /**
     * 符号键的目标墨迹高度（占键高比例）。
     *
     * 箭头字形又宽又扁，同样墨迹高度下视觉重量远大于字母和数字 —— 0.34 时墨迹约 28px
     * （字母约 24px），字面高度只差 4px，但横向占满大半个键宽，看上去仍然偏粗。
     * 降到 0.30 后墨迹约 25px，与字母基本齐平；回车下方还有 0.36，让它在视觉上
     * 略重于箭头，符合「确认键比移动键更重」的层级。
     */
    private const val INK_ARROW = 0.30f
    private const val INK_ENTER = 0.36f
    private const val INK_BACKSPACE = 0.34f
    private const val INK_SHIFT = 0.34f

    private fun c(ch: String) = KeyDef(ch, KeyAction.Char(ch))

    fun letters(zh: Boolean): KeyLayout {
        val rows = ArrayList<KeyRow>(5)

        rows.add(KeyRow("1234567890".map { c(it.toString()) }))
        rows.add(KeyRow("qwertyuiop".map { c(it.toString()) }))
        rows.add(
            KeyRow(
                "asdfghjkl".map { c(it.toString()) } +
                    KeyDef("⌫", KeyAction.Backspace, inkRatio = INK_BACKSPACE, functional = true)
            )
        )
        rows.add(
            KeyRow(
                listOf(KeyDef("⇧", KeyAction.Shift, 1.3f, inkRatio = INK_SHIFT, functional = true)) +
                    "zxcvbnm".map { c(it.toString()) } +
                    listOf(c(","), c("."))
            )
        )
        rows.add(
            KeyRow(
                listOf(
                    KeyDef(if (zh) "中" else "Abc", KeyAction.SwitchLang, 1.3f, true),
                    /*
                     * 符号页入口。之前 switchLayout() 与 symbols() 都写好了，唯独没有
                     * 任何键绑到 SwitchLayout，导致标点符号页根本进不去 —— 只有
                     * `,` `.` 两个键自带标点，其余全无入口。放在语言键右侧，
                     * 与符号页的 `abc` 同一列，来回切换位置固定。
                     */
                    KeyDef("?123", KeyAction.SwitchLayout, 1.3f, true),
                    KeyDef("←", KeyAction.CursorLeft, 1.0f, true, inkRatio = INK_ARROW),
                    KeyDef("→", KeyAction.CursorRight, 1.0f, true, inkRatio = INK_ARROW),
                    KeyDef("空格", KeyAction.Space, 3.0f, true),
                    KeyDef("↵", KeyAction.Enter, 1.1f, true, inkRatio = INK_ENTER),
                    /*
                     * 收起键盘。原来的 ⚙ 打开的是本应用设置页，而"键盘能显示出来"
                     * 本身就意味着已经启用并选中，页面里那两步引导永远用不上；
                     * 真正需要引导的首次装机又根本按不到这个键。位置让给每次都按的操作。
                     * 设置页仍有入口：遥控器 MENU 键，或桌面图标。
                     *
                     * 键面画矢量折角而不用文字或 Unicode 字符：`收起` 两个字在 0.9 权重
                     * 的窄键里挤，`⌄ ⏷` 这类字符又受字体缺字影响，尺寸也不可控。
                     */
                    KeyDef("收起", KeyAction.Hide, 0.9f, true, icon = KeyIcon.HIDE)
                )
            )
        )

        return KeyLayout(
            id = if (zh) ID_LETTERS_ZH else ID_LETTERS_EN,
            rows = rows,
            /*
             * 英文页也要这条栏。它只占 30dp，但少了它就是"中英切换整个键盘变高变矮、
             * 右侧二维码跟着上下跳"——比多一行空条难受得多。英文态没有候选词，
             * 这条栏就用来常驻显示"当前是英文"，顺带解决模式不可见的问题。
             */
            showCandidates = true,
            hint = if (zh) "拼音输入 · 输入字母后出候选" else "英文输入 · 按键直接上屏"
        )
    }

    fun symbols(zh: Boolean): KeyLayout {
        val rows = ArrayList<KeyRow>(5)
        /*
         * 中英两页的符号行数必须都是 4，否则切语言时整个键盘的行高会跟着变。
         * 中文模式用前两行换掉等量的半角符号：中文输入最常按的是「，。？！」这类，
         * 放最上面一行按一次就到；`;:'",./?<>` 与 `!@#$%^&*()` 两页共用。
         */
        if (zh) {
            rows.add(KeyRow("，。？！；：、…—～".map { c(it.toString()) }))
            rows.add(KeyRow("“”‘’（）《》【】".map { c(it.toString()) }))
        } else {
            rows.add(KeyRow("!@#$%^&*()".map { c(it.toString()) }))
            rows.add(KeyRow("-_=+[]{}|\\".map { c(it.toString()) }))
        }
        rows.add(KeyRow(";:'\",./?<>".map { c(it.toString()) }))
        if (!zh) {
            rows.add(KeyRow("~`€£¥§°±×÷".map { c(it.toString()) }))
        } else {
            rows.add(KeyRow("!@#$%^&*()".map { c(it.toString()) }))
        }
        rows.add(
            KeyRow(
                listOf(
                    KeyDef("abc", KeyAction.SwitchLayout, 1.3f, true),
                    // 与字母页底部行同列：方向键位置两页一致，切页后手不用重新找
                    KeyDef("←", KeyAction.CursorLeft, 1.0f, true, inkRatio = INK_ARROW),
                    KeyDef("→", KeyAction.CursorRight, 1.0f, true, inkRatio = INK_ARROW),
                    KeyDef("空格", KeyAction.Space, 2.4f, true),
                    KeyDef("↵", KeyAction.Enter, 1.1f, true, inkRatio = INK_ENTER),
                    KeyDef("⌫", KeyAction.Backspace, 1.0f, true, inkRatio = INK_BACKSPACE),
                    KeyDef("收起", KeyAction.Hide, 0.9f, true, icon = KeyIcon.HIDE)
                )
            )
        )
        // 符号页同样保留这条栏，避免"切符号页时键盘又矮一截"
        return KeyLayout(
            ID_SYMBOLS,
            rows,
            showCandidates = true,
            hint = "符号页 · 按 abc 回字母"
        )
    }
}
