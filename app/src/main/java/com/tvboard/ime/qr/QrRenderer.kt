package com.tvboard.ime.qr

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.util.concurrent.Executors

/**
 * 二维码编码。
 *
 * 两条要点：
 * 1. 按目标像素直接编码，不做位图缩放 —— 插值会模糊模块边界，远距离识别率断崖下跌；
 * 2. A53 级 CPU 上单次编码约 30–80ms，必须离开主线程，且结果要缓存。
 */
object QrRenderer {

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "qr-render").apply { priority = Thread.NORM_PRIORITY - 1 }
    }

    private val main = Handler(Looper.getMainLooper())

    fun encode(content: String, sizePx: Int, callback: (Bitmap?) -> Unit) {
        if (sizePx <= 0 || content.isEmpty()) {
            callback(null)
            return
        }
        executor.execute {
            val bitmap = try {
                render(content, sizePx)
            } catch (ignored: Throwable) {
                null
            }
            main.post { callback(bitmap) }
        }
    }

    private fun render(content: String, sizePx: Int): Bitmap {
        val hints = HashMap<EncodeHintType, Any>()
        hints[EncodeHintType.CHARACTER_SET] = "UTF-8"
        hints[EncodeHintType.ERROR_CORRECTION] = ErrorCorrectionLevel.L
        hints[EncodeHintType.MARGIN] = 2

        val matrix = QRCodeWriter().encode(
            content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints
        )
        val width = matrix.width
        val height = matrix.height
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            val offset = y * width
            for (x in 0 until width) {
                pixels[offset + x] = if (matrix.get(x, y)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
            }
        }
        // RGB_565：黑白图无色彩损失，内存减半，512MB 设备上这点很关键
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return bitmap
    }
}
