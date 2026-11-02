package com.tvboard.ime.qr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import com.tvboard.ime.R
import com.tvboard.ime.transfer.TransferState
import com.tvboard.ime.util.dp

/**
 * 常驻二维码面板。
 *
 * 三个约束：
 * 1. 不可聚焦 —— 纯展示区，D-pad 一旦进入就是"焦点黑洞"，用户只能按返回退出键盘；
 * 2. 只在 endpoint 变化时重新编码，状态文案变化只重绘文字；
 * 3. Bitmap 只保留一份，换图前必须 recycle（512MB 设备上两张 428² 就足以触发 OOM）。
 *
 * 面板没有任何点击语义：只展示二维码、地址与连接状态。
 */
class QrPanel @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bitmapPaint = Paint().apply {
        isAntiAlias = false
        isFilterBitmap = false // 最近邻缩放，保持模块边缘锐利
        isDither = false
    }
    private val ipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.MONOSPACE
    }
    private val statusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }
    private val placeholderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }
    private val roundRect = RectF()
    private val dst = Rect()

    private var bitmap: Bitmap? = null
    private var cacheKey: String? = null
    private var endpoint: String = ""
    private var status: String = ""
    private var statusOk = false

    init {
        isFocusable = false
        isFocusableInTouchMode = false
        setWillNotDraw(false)
        bgPaint.color = ContextCompat.getColor(context, R.color.bg_panel)
        ipPaint.color = ContextCompat.getColor(context, R.color.text_primary)
        statusPaint.color = ContextCompat.getColor(context, R.color.text_secondary)
        placeholderPaint.color = ContextCompat.getColor(context, R.color.text_secondary)
    }

    fun render(state: TransferState) {
        when (state) {
            is TransferState.Ready -> applyEndpoint(
                "http://${state.ip}:${state.port}",
                context.getString(R.string.qr_waiting),
                false
            )

            is TransferState.Connected -> applyEndpoint(
                "http://${state.ip}:${state.port}",
                // 不展示连接数：服务端只接纳单个手机端会话，这个数字恒为 1，
                // 摆出来既占地方又让人以为可以连第二台。
                context.getString(R.string.qr_connected),
                true
            )

            TransferState.NoNetwork -> {
                dropBitmap()
                endpoint = ""
                status = context.getString(R.string.qr_no_network)
                statusOk = false
                invalidate()
            }

            TransferState.Starting, TransferState.Suspended, TransferState.Idle -> {
                status = context.getString(R.string.qr_starting)
                statusOk = false
                invalidate()
            }
        }
    }

    private fun applyEndpoint(text: String, statusText: String, ok: Boolean) {
        endpoint = text
        status = statusText
        statusOk = ok
        if (cacheKey != text) {
            cacheKey = text
            requestBitmap(text)
        }
        invalidate()
    }

    private fun requestBitmap(content: String) {
        val side = qrSidePx()
        if (side <= 0 || width == 0) return
        QrRenderer.encode(content, side) { result ->
            if (cacheKey != content) {
                result?.recycle()
                return@encode
            }
            if (result == null) return@encode
            bitmap?.recycle()
            bitmap = result
            invalidate()
        }
    }

    private fun dropBitmap() {
        bitmap?.recycle()
        bitmap = null
        cacheKey = null
    }

    private fun qrSidePx(): Int {
        val pad = dp(10f)
        // 二维码下方：地址一行 + 状态一行。地址不再拆两行后，这块高度从 58dp 收到 46dp，
        // 收回的 12dp 全部给二维码（1080p 下边长 +24px，扫码距离同步变好）
        val textBlock = dp(46f)
        val availableW = width - pad * 2
        val availableH = height - pad * 2 - textBlock
        if (availableW <= 0 || availableH <= 0) return 0
        return if (availableW < availableH) availableW else availableH
    }

    /**
     * 面板不响应任何点击，但仍然吃掉触摸。
     *
     * 不吃掉的话事件会交给父容器继续派发，部分 ROM 会把它当成
     * 「点到输入法窗口外面」而顺手收起键盘——面板占的面积不小，误触概率很高。
     */
    override fun onTouchEvent(event: MotionEvent): Boolean = true

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // 尺寸变化后原 Bitmap 边长不再匹配，重新编码
        val key = cacheKey
        if (key != null && (bitmap == null || bitmap!!.width != qrSidePx())) {
            bitmap?.recycle()
            bitmap = null
            requestBitmap(key)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        dropBitmap()
    }

    override fun onDraw(canvas: Canvas) {
        val pad = dp(10f).toFloat()
        val radius = dp(8f).toFloat()

        roundRect.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(roundRect, radius, radius, bgPaint)

        val side = qrSidePx()
        val left = (width - side) / 2f
        val top = pad

        val bmp = bitmap
        if (bmp != null && side > 0) {
            dst.set(left.toInt(), top.toInt(), (left + side).toInt(), (top + side).toInt())
            canvas.drawBitmap(bmp, null, dst, bitmapPaint)
        } else if (side > 0) {
            placeholderPaint.textSize = dp(10f).toFloat()
            canvas.drawText(
                context.getString(R.string.qr_starting),
                width / 2f,
                top + side / 2f,
                placeholderPaint
            )
        }

        ipPaint.textSize = dp(12f).toFloat()
        statusPaint.textSize = dp(10f).toFloat()
        statusPaint.color = ContextCompat.getColor(
            context,
            if (statusOk) R.color.status_ok else R.color.text_secondary
        )

        val baseY = top + side + dp(18f)
        if (endpoint.isNotEmpty()) {
            drawEndpoint(canvas, baseY)
        }

        if (status.isNotEmpty()) {
            val dot = if (statusOk) "● " else "○ "
            val y = if (endpoint.isNotEmpty()) baseY + dp(16f) else baseY
            if (y < height - dp(4f)) {
                canvas.drawText(dot + status, width / 2f, y, statusPaint)
            }
        }
    }

    /**
     * 地址必须单行。
     *
     * 面板只有屏宽的 24%，之前把 host 和 ":端口" 拆成两行（当时的写法见设计文档
     * 5.5 的距离推导），看起来像两条信息，用户明确要求不换行。这里改成单行，
     * 宽度不够时按比例缩字号（下限 8dp），宁可字小也不折行——折行的地址是
     * 让人怀疑"端口是哪一段"的根因。
     */
    private fun drawEndpoint(canvas: Canvas, baseY: Float) {
        val text = endpoint.removePrefix("http://")
        val available = (width - dp(20f)).toFloat()
        val measured = ipPaint.measureText(text)
        if (measured > available && measured > 0f) {
            ipPaint.textSize = (ipPaint.textSize * available / measured)
                .coerceAtLeast(dp(8f).toFloat())
        }
        canvas.drawText(text, width / 2f, baseY, ipPaint)
    }
}
