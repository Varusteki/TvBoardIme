package com.tvboard.ime.ime

import android.content.Intent
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import com.tvboard.ime.inject.TextInjector
import com.tvboard.ime.transfer.TextQueue
import com.tvboard.ime.transfer.TransferHost
import com.tvboard.ime.transfer.TransferState
import com.tvboard.ime.ui.SettingsActivity

/**
 * 输入法服务。
 *
 * 生命周期与传输宿主的对应关系：
 *   onCreate              → 创建 TransferHost（不启动）
 *   onCreateInputView     → 创建根布局，必要时启动 TransferHost
 *   onStartInputView      → resume（取消待停止定时器），消费待注入队列
 *   onFinishInputView     → scheduleStop(60s)，避免频繁开关键盘导致端口抖动
 *   onDestroy             → 关闭 socket
 */
class TvImeService : InputMethodService(), RootLayout.Host {

    private var root: RootLayout? = null
    private var transferHost: TransferHost? = null
    private val main = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        transferHost = TransferHost(this, object : TransferHost.Callback {
            override fun onStateChanged(state: TransferState) {
                root?.qrPanel?.render(state)
            }

            override fun onTextDelivered(injected: Boolean, length: Int) {
                if (injected) {
                    toast("已写入 $length 字")
                } else {
                    toast("已暂存 $length 字，点开输入框后自动填入")
                }
            }
        })
    }

    override fun onCreateInputView(): View {
        val view = RootLayout(this)
        view.host = this
        view.keyboard.onCommit = { text -> TextInjector.commit(text) }
        view.keyboard.onBackspace = { TextInjector.backspace(1) }
        root = view

        transferHost?.let { host ->
            view.qrPanel.render(host.state)
            host.start()
        }
        return view
    }

    /** TV 端禁止抽取模式：全屏编辑会遮挡键盘并破坏焦点 */
    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onStartInput(editorInfo: EditorInfo, restarting: Boolean) {
        super.onStartInput(editorInfo, restarting)
        TextInjector.attach(currentInputConnection, editorInfo)
        root?.keyboard?.applyEditorInfo(editorInfo)
    }

    override fun onFinishInput() {
        TextInjector.detach()
        super.onFinishInput()
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        transferHost?.resume()
        // API 21 上 currentInputConnection 的可用时机偶有错位，这里补挂一次
        TextInjector.attach(currentInputConnection, info)
        TextQueue.init(this)
        TextQueue.drain()
        // 键盘刚弹出时焦点必须在键盘内，否则第一次按方向键没有任何反馈
        root?.keyboard?.ensureFocus()
    }

    /**
     * 按键兜底通道。
     *
     * 正常情况下按键走 View 链（RootLayout.dispatchKeyEvent）。但 IME 窗口的
     * 按键也可能只到 InputMethodService 这一层（取决于窗口实现与 ROM），
     * 那时 View 链收不到，表现就是"遥控器完全没反应"。这里转发一次，
     * 两个通道由 RootLayout 按 (keyCode, eventTime) 去重，不会重复执行。
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (root?.handleKeyEvent(event) == true) return true
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (root?.handleKeyEvent(event) == true) return true
        return super.onKeyUp(keyCode, event)
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        transferHost?.scheduleStop()
    }

    /** InputMethodService 没有 onDestroyInputView 钩子，视图引用只能在这里释放 */
    override fun onDestroy() {
        root = null
        transferHost?.shutdown()
        transferHost = null
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // ---------------------------------------------------------------- Host

    override fun onRequestHide() {
        requestHideSelf(0)
    }

    override fun onOpenSettings() {
        try {
            startActivity(
                Intent(this, SettingsActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (ignored: Throwable) {
        }
    }

    override fun onCursorMove(delta: Int) {
        TextInjector.sendKey(
            if (delta < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
        )
    }

    private fun toast(text: String) {
        try {
            Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
        } catch (ignored: Throwable) {
        }
    }
}
