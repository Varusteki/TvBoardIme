package com.tvboard.ime.transfer

import android.content.Context
import com.tvboard.ime.BuildConfig
import java.io.File

/**
 * 二维码里对外公示的地址覆盖（仅 debug 构建生效）。
 *
 * 存在的理由：AVD 跑在 QEMU 用户态 NAT 后面，拿到的地址永远是 10.0.2.15，
 * 手机没有任何路由能到这个地址；而 adb forward 只监听 127.0.0.1，手机同样连不上。
 * 联调时链路是「手机 → PC 局域网 IP:8080 → 127.0.0.1:18080（adb forward）→ 模拟器:8080」，
 * 因此二维码必须写宿主机地址，不能写模拟器自己的。
 *
 * 覆盖文件内容一行，`192.168.1.23:8080` 或只写 `192.168.1.23`（端口沿用本机实际端口）。
 * 两个位置都认，内部目录优先：
 *   /data/data/<pkg>/files/endpoint.txt      ← adb shell run-as 可写，权限稳
 *   /sdcard/Android/data/<pkg>/files/endpoint.txt
 *
 * 为什么优先内部目录：Android 11+ 的分区存储里，`adb shell echo >` 写进
 * `/sdcard/Android/data/<pkg>/` 的文件属主是 shell，应用自己反而读不到
 * （Permission denied，chmod 也改不回来）。`adb shell run-as <pkg>` 才是
 * 以应用身份写，写出的文件应用一定读得到。
 *
 * release 包里 `BuildConfig.DEBUG` 为 false，此函数恒返回 null，逻辑与真机一致。
 */
internal object DebugEndpoint {

    fun read(context: Context): Pair<String, Int>? {
        if (!BuildConfig.DEBUG) return null
        val text = readText(File(context.filesDir, "endpoint.txt"))
            ?: readText(context.getExternalFilesDir(null)?.let { File(it, "endpoint.txt") })
            ?: return null
        if (text.isEmpty()) return null
        val host = text.substringBefore(':').trim()
        if (host.isEmpty()) return null
        val port = text.substringAfter(':', "").trim().toIntOrNull() ?: 0
        return host to port
    }

    private fun readText(file: File?): String? {
        if (file == null || !file.isFile) return null
        return try {
            file.readText().trim()
        } catch (ignored: Exception) {
            null
        }
    }
}
