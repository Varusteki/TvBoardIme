package com.tvboard.ime.inject

import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection

/**
 * 全部文本写入的唯一出口。
 *
 * 三条硬规则：
 * 1. InputConnection 只能在主线程使用，所有外部调用（HTTP 线程）必须 post 过来；
 * 2. 单次 commitText 超过约 1KB 可能触发 Binder 事务超限，按 512 字符切片；
 * 3. WebView / 自绘输入框对 deleteSurroundingText 支持不完整，失败降级为 KEYCODE_DEL。
 */
object TextInjector {

    private const val CHUNK = 512

    /** ExtractedText 不可用时退化用的删除长度：远大于任何输入框的实际长度，实现侧会自行截断 */
    private const val FALLBACK_CLEAR = 100_000

    @Volatile
    private var connection: InputConnection? = null

    @Volatile
    private var editorInfo: EditorInfo? = null

    private val main = Handler(Looper.getMainLooper())

    fun attach(conn: InputConnection?, info: EditorInfo?) {
        connection = conn
        editorInfo = info
    }

    fun detach() {
        connection = null
        editorInfo = null
    }

    /** 是否可写入。注意：判据是 connection 非空，而非键盘是否"可见" */
    fun canInject(): Boolean = connection != null && !isPasswordField(editorInfo)

    fun currentInputType(): Int = editorInfo?.inputType ?: 0

    /** 当前输入框是否为密码框：手机端据此置灰发送按钮 */
    fun isPasswordCurrent(): Boolean = isPasswordField(editorInfo)

    fun commit(text: String, onResult: ((Boolean) -> Unit)? = null) {
        if (text.isEmpty()) {
            onResult?.invoke(true)
            return
        }
        if (connection == null) {
            onResult?.invoke(false)
            return
        }
        main.post {
            val conn = connection
            if (conn == null) {
                onResult?.invoke(false)
                return@post
            }
            try {
                conn.beginBatchEdit()
                var index = 0
                while (index < text.length) {
                    val end = if (index + CHUNK > text.length) text.length else index + CHUNK
                    conn.commitText(text.substring(index, end), 1)
                    index = end
                }
            } catch (t: Throwable) {
                onResult?.invoke(false)
                return@post
            } finally {
                try {
                    conn.endBatchEdit()
                } catch (ignored: Throwable) {
                }
            }
            onResult?.invoke(true)
        }
    }

    fun backspace(count: Int = 1) {
        if (connection == null) return
        main.post {
            val conn = connection ?: return@post
            var ok = false
            try {
                ok = conn.deleteSurroundingText(count, 0)
            } catch (ignored: Throwable) {
                ok = false
            }
            if (!ok) {
                repeat(count) { tapKey(conn, KeyEvent.KEYCODE_DEL) }
            }
        }
    }

    /** 光标移动 / 回车等，交给目标应用自行处理 */
    fun sendKey(keyCode: Int) {
        if (connection == null) return
        main.post {
            val conn = connection ?: return@post
            tapKey(conn, keyCode)
        }
    }

    /**
     * 清空当前输入框（光标前后一起删）。
     *
     * 先按 [getExtractedText] 精确算出光标前后各有多少字符，再删对应数量；只有拿不到
     * ExtractedText（部分 WebView / 自绘输入框不实现）时才退化成传一个大数。
     *
     * 为什么不一上来就传大数：`deleteSurroundingText(MAX, MAX)` 在各家实现里的行为
     * 不统一，有的直接返回 false 什么都不删，有的会把整段可编辑内容抹掉（包括本该
     * 保留的部分）。能算出准确长度时就走准确路径。
     */
    fun clearAll(onResult: ((Boolean) -> Unit)? = null) {
        if (connection == null) {
            onResult?.invoke(false)
            return
        }
        main.post {
            val conn = connection
            if (conn == null) {
                onResult?.invoke(false)
                return@post
            }
            var ok = false
            try {
                val request = ExtractedTextRequest().apply {
                    hintMaxChars = 10_000
                    hintMaxLines = 10
                    flags = 0
                }
                val et = conn.getExtractedText(request, 0)
                val text = et?.text
                if (et != null && text != null && et.selectionStart >= 0 && et.selectionEnd >= 0) {
                    // startOffset 非 0 说明目标只暴露了窗口内的片段，前面还有内容要一起删
                    val before = et.startOffset + et.selectionStart
                    val after = text.length - et.selectionEnd
                    ok = conn.deleteSurroundingText(before.coerceAtLeast(0), after.coerceAtLeast(0))
                }
            } catch (ignored: Throwable) {
                ok = false
            }
            if (!ok) {
                ok = try {
                    conn.deleteSurroundingText(FALLBACK_CLEAR, FALLBACK_CLEAR)
                } catch (ignored: Throwable) {
                    false
                }
            }
            onResult?.invoke(ok)
        }
    }

    private fun tapKey(conn: InputConnection, keyCode: Int) {
        val now = android.os.SystemClock.uptimeMillis()
        conn.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
        conn.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0))
    }

    fun isPasswordField(info: EditorInfo?): Boolean {
        val inputType = info?.inputType ?: return false
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        val cls = inputType and InputType.TYPE_MASK_CLASS
        if (cls != InputType.TYPE_CLASS_TEXT && cls != InputType.TYPE_CLASS_NUMBER) return false
        return variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
            variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
    }
}
