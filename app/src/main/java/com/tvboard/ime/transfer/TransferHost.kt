package com.tvboard.ime.transfer

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import com.tvboard.ime.inject.TextInjector
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject

/**
 * 传输宿主。
 *
 * 不做独立前台服务：二维码只在 IME 可见时可被扫到，IME 隐藏即无入口，
 * 独立服务的进程协调与存活管理成本换不到收益（见设计文档 D2）。
 *
 * 只承载文字：文件投送已移除，扫码后能做的只有发文字（见设计文档变更记录）。
 */
class TransferHost(
    private val context: Context,
    private val callback: Callback
) {

    interface Callback {
        fun onStateChanged(state: TransferState)
        fun onTextDelivered(injected: Boolean, length: Int)
    }

    private companion object {
        val PORT_RANGE = 8080..8090
        const val SESSION_IDLE_MS = 15_000L
        const val STOP_GRACE_MS = 60_000L
    }

    private val main = Handler(Looper.getMainLooper())

    private var server: LocalHttpServer? = null
    private var port = 0
    private var sessionActive = false
    private var lastTouchAt = 0L

    private var stopRunnable: Runnable? = null
    private var idleRunnable: Runnable? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    var state: TransferState = TransferState.Idle
        private set

    val isRunning: Boolean
        get() = server != null

    fun start() {
        if (server != null) return
        TextQueue.init(context)
        publish(TransferState.Starting)

        val chosenPort = PortAllocator.findFree(PORT_RANGE)
        val local = LocalHttpServer(chosenPort, context.assets, api)
        try {
            local.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
        } catch (ignored: Throwable) {
            publish(TransferState.Idle)
            return
        }
        server = local
        port = chosenPort

        registerNetworkCallback()
        refreshEndpoint()
    }

    /** IME 隐藏后延迟停止，避免频繁开关键盘导致端口抖动 */
    fun scheduleStop(graceMs: Long = STOP_GRACE_MS) {
        cancelStop()
        val runnable = Runnable {
            stopRunnable = null
            shutdown()
        }
        stopRunnable = runnable
        main.postDelayed(runnable, graceMs)
        if (server != null) publish(TransferState.Suspended)
    }

    fun resume() {
        cancelStop()
        if (server == null) start() else refreshEndpoint()
    }

    fun shutdown() {
        cancelStop()
        cancelIdleCheck()

        networkCallback?.let { cb ->
            try {
                connectivityManager()?.unregisterNetworkCallback(cb)
            } catch (ignored: Throwable) {
            }
        }
        networkCallback = null

        try {
            server?.stop()
        } catch (ignored: Throwable) {
        }
        server = null
        sessionActive = false
        publish(TransferState.Idle)
    }

    // ---------------------------------------------------------------- 内部

    private fun cancelStop() {
        stopRunnable?.let { main.removeCallbacks(it) }
        stopRunnable = null
    }

    private fun cancelIdleCheck() {
        idleRunnable?.let { main.removeCallbacks(it) }
        idleRunnable = null
    }

    private fun publish(next: TransferState) {
        state = next
        callback.onStateChanged(next)
    }

    private fun refreshEndpoint() {
        if (server == null) return
        // 模拟器联调时用宿主机地址覆盖（见 DebugEndpoint）；真机/debug 文件不存在时走正常探测
        val override = DebugEndpoint.read(context)
        val localIp = override?.first ?: LanAddress.current(context)?.address?.hostAddress
        if (localIp == null) {
            publish(TransferState.NoNetwork)
            return
        }
        val shownPort = override?.second?.takeIf { it in 1..65535 } ?: port
        publish(
            if (sessionActive) TransferState.Connected(localIp, shownPort, 1)
            else TransferState.Ready(localIp, shownPort)
        )
    }

    private fun connectivityManager(): ConnectivityManager? =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    /** registerDefaultNetworkCallback 是 API 24+，低版本必须用 NetworkRequest 版本 */
    private fun registerNetworkCallback() {
        if (networkCallback != null) return
        val cm = connectivityManager() ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                main.post { refreshEndpoint() }
            }

            override fun onLost(network: Network) {
                main.post { refreshEndpoint() }
            }

            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                main.post { refreshEndpoint() }
            }
        }
        try {
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            cm.registerNetworkCallback(request, cb)
            networkCallback = cb
        } catch (ignored: Throwable) {
        }
    }

    private fun onSessionTouch() {
        main.post {
            lastTouchAt = SystemClock.uptimeMillis()
            if (!sessionActive) {
                sessionActive = true
                refreshEndpoint()
            }
            scheduleIdleCheck()
        }
    }

    private fun scheduleIdleCheck() {
        cancelIdleCheck()
        val runnable = Runnable {
            idleRunnable = null
            if (SystemClock.uptimeMillis() - lastTouchAt >= SESSION_IDLE_MS) {
                sessionActive = false
                refreshEndpoint()
            }
        }
        idleRunnable = runnable
        main.postDelayed(runnable, SESSION_IDLE_MS + 1_000L)
    }

    private fun deliverText(text: String): Boolean {
        val injected = TextInjector.canInject()
        if (injected) TextInjector.commit(text) else TextQueue.enqueue(text)
        main.post { callback.onTextDelivered(injected, text.length) }
        return injected
    }

    /**
     * 手机端的光标 / 删除 / 清空指令。
     *
     * 与 [deliverText] 不同，这些指令没有"先排队、等输入框出现再执行"的语义 ——
     * 排队执行一个"退格"毫无意义（等到输入框出现时，用户早就不在那个上下文里了）。
     * 所以没有可用输入框时直接返回 false，由手机端提示，不落队列。
     */
    private fun deliverKey(name: String): Boolean {
        if (!TextInjector.canInject()) return false
        when (name) {
            "left" -> TextInjector.sendKey(KeyEvent.KEYCODE_DPAD_LEFT)
            "right" -> TextInjector.sendKey(KeyEvent.KEYCODE_DPAD_RIGHT)
            "backspace" -> TextInjector.backspace(1)
            "clear" -> TextInjector.clearAll()
            else -> return false
        }
        return true
    }

    private fun stateJson(): String {
        return JSONObject()
            .put("hasInput", TextInjector.canInject())
            .put("isPassword", TextInjector.isPasswordCurrent())
            .put("deviceName", Build.MODEL ?: "TV")
            .put("queueSize", TextQueue.size())
            .toString()
    }

    private val api = object : LocalHttpServer.Api {

        override fun stateJson(): String = this@TransferHost.stateJson()

        override fun receiveText(text: String): Boolean = deliverText(text)

        override fun sendKey(name: String): Boolean = deliverKey(name)

        override fun onSessionTouch() = this@TransferHost.onSessionTouch()
    }
}
