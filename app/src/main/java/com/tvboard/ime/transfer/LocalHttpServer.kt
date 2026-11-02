package com.tvboard.ime.transfer

import android.content.res.AssetManager
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject

/**
 * 极简 HTTP 服务。
 *
 * 无鉴权 —— 依赖"局域网可信 + 二维码即凭据"的信任模型（见设计文档第 8 章）。
 * 绑定 0.0.0.0：IP 变化时无需重启服务，只需重绘二维码。
 */
class LocalHttpServer(
    port: Int,
    private val assets: AssetManager,
    private val api: Api
) : NanoHTTPD(port) {

    interface Api {
        fun stateJson(): String

        /** true 表示已直接写入输入框，false 表示进入待用队列 */
        fun receiveText(text: String): Boolean

        /**
         * 执行一个控制指令。true 表示已作用到输入框。
         *
         * name 只会是 [KEYS] 里的值，白名单校验在服务端完成。
         */
        fun sendKey(name: String): Boolean

        fun onSessionTouch()
    }

    companion object {
        private const val MIME_HTML = "text/html; charset=utf-8"
        private const val MIME_JSON = "application/json; charset=utf-8"
        private const val MIME_TEXT = "text/plain; charset=utf-8"

        /** 纯文字请求，64 KB 足够；同时挡掉超大 body 占内存 */
        private const val MAX_BODY_BYTES = 64 * 1024L

        /**
         * 控制指令白名单。
         *
         * 手机端只能发这几个名字，服务端不接受任意键码 —— 否则等于把这台电视的
         * 输入法变成了任意键事件注入器，超出"跨屏输入"这个能力边界。
         */
        val KEYS = setOf("left", "right", "backspace", "clear")
    }

    private var cachedIndex: String? = null

    override fun serve(session: IHTTPSession): Response {
        api.onSessionTouch()
        if (session.method == Method.OPTIONS) {
            return newFixedLengthResponse(Response.Status.OK, MIME_TEXT, "")
        }
        val uri = session.uri ?: "/"
        return try {
            when {
                session.method == Method.GET && (uri == "/" || uri == "/index.html") -> serveIndex()
                session.method == Method.GET && uri == "/api/state" -> json(api.stateJson())
                session.method == Method.POST && uri == "/api/text" -> handleText(session)
                session.method == Method.POST && uri == "/api/key" -> handleKey(session)
                else -> newFixedLengthResponse(
                    Response.Status.NOT_FOUND, MIME_TEXT, "not found"
                )
            }
        } catch (t: Throwable) {
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                MIME_TEXT,
                "error: " + (t.message ?: t.javaClass.simpleName)
            )
        }
    }

    private fun serveIndex(): Response {
        var html = cachedIndex
        if (html == null) {
            html = try {
                assets.open("web/index.html").bufferedReader().use { it.readText() }
            } catch (ignored: Throwable) {
                "<!doctype html><meta charset=utf-8><body>页面资源缺失</body>"
            }
            cachedIndex = html
        }
        val response = newFixedLengthResponse(Response.Status.OK, MIME_HTML, html)
        response.addHeader("Cache-Control", "no-store")
        return response
    }

    private fun handleText(session: IHTTPSession): Response {
        val raw = readBodyUtf8(session)
        val text = try {
            JSONObject(raw).optString("text", "")
        } catch (ignored: Throwable) {
            ""
        }
        if (text.isEmpty()) {
            return json(JSONObject().put("ok", false).put("reason", "empty").toString())
        }
        val injected = api.receiveText(text)
        return json(
            JSONObject()
                .put("ok", true)
                .put("mode", if (injected) "injected" else "queued")
                .toString()
        )
    }

    /**
     * 控制指令：`{"key":"left"|"right"|"backspace"|"clear"}`。
     *
     * 与 `/api/text` 分开而不是塞进同一个端点，因为语义完全不同：这条不产生新内容，
     * 只操作电视上已有的输入框状态，失败原因也完全不同（没有输入框 / 密码框）。
     */
    private fun handleKey(session: IHTTPSession): Response {
        val raw = readBodyUtf8(session)
        val key = try {
            JSONObject(raw).optString("key", "")
        } catch (ignored: Throwable) {
            ""
        }
        if (key.isEmpty()) {
            return json(JSONObject().put("ok", false).put("reason", "empty").toString())
        }
        if (key !in KEYS) {
            return json(JSONObject().put("ok", false).put("reason", "unknown-key").toString())
        }
        val done = api.sendKey(key)
        return json(
            JSONObject()
                .put("ok", done)
                .put("key", key)
                .put("reason", if (done) "" else "no-input")
                .toString()
        )
    }

    /**
     * 自己按 Content-Length 读原始字节，显式按 UTF-8 解码。
     *
     * 不能用 `session.parseBody()`：NanoHTTPD 2.3.1 内部是
     * `new String(bytes, ContentType.getEncoding())`，而 `getEncoding()` 在请求头
     * 没写 `charset` 时**回落到 US-ASCII**。浏览器发的是
     * `Content-Type: application/json`（不会自动补 charset），于是每个 UTF-8 字节
     * 都被替换成 U+FFFD —— 电视端就显示成一串乱码方块（实测"中文测试"变成
     * 12 个 U+FFFD）。顺带避开 parseBody 按固定缓冲读 body 时的长文本截断。
     */
    private fun readBodyUtf8(session: IHTTPSession): String {
        val declared = session.headers?.get("content-length")?.trim()?.toLongOrNull() ?: -1L
        if (declared == 0L) return ""
        if (declared > MAX_BODY_BYTES) return ""

        if (declared < 0) {
            // 没有 Content-Length（HTTP/1.0 客户端或畸形请求）：退回框架解析，
            // 编码仍可能不准，但至少不丢请求
            val body = HashMap<String, String>()
            session.parseBody(body)
            return body["postData"] ?: ""
        }

        val input = session.inputStream ?: return ""
        val out = java.io.ByteArrayOutputStream(declared.toInt())
        val buf = ByteArray(4096)
        var remaining = declared
        while (remaining > 0) {
            val want = minOf(buf.size.toLong(), remaining).toInt()
            val read = input.read(buf, 0, want)
            if (read <= 0) break
            out.write(buf, 0, read)
            remaining -= read
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }

    private fun json(body: String): Response {
        val response = newFixedLengthResponse(Response.Status.OK, MIME_JSON, body)
        response.addHeader("Cache-Control", "no-store")
        return response
    }
}

/** 端口探测：8080–8090 顺延，全占用则取系统随机端口 */
object PortAllocator {

    fun findFree(range: IntRange): Int {
        for (port in range) {
            try {
                java.net.ServerSocket(port).use { return port }
            } catch (ignored: Throwable) {
                // 被占用，继续下一个
            }
        }
        return try {
            java.net.ServerSocket(0).use { it.localPort }
        } catch (t: Throwable) {
            8080
        }
    }
}
