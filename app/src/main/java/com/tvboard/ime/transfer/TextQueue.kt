package com.tvboard.ime.transfer

import android.content.Context
import com.tvboard.ime.inject.TextInjector
import org.json.JSONArray

/**
 * 待注入文本队列。
 *
 * 存在意义：手机扫码后，用户可能已经切走输入框（IME 收起）。
 * 此时文本不能丢，也不能硬写到一个已失效的 InputConnection 上。
 */
object TextQueue {

    private const val MAX = 20
    private const val PREF_NAME = "tvboard_text_queue"
    private const val KEY_ITEMS = "items"

    private val items = ArrayList<String>()
    private var appContext: Context? = null
    private var restored = false

    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        restore()
    }

    @Synchronized
    fun size(): Int = items.size

    @Synchronized
    fun enqueue(text: String) {
        if (text.isEmpty()) return
        items.add(text)
        while (items.size > MAX) items.removeAt(0)
        persist()
    }

    @Synchronized
    fun clear() {
        items.clear()
        persist()
    }

    /**
     * 主线程调用。把暂存内容依次写入当前输入框。
     * 返回实际写入的条数，0 表示当前没有可用输入框。
     */
    @Synchronized
    fun drain(): Int {
        if (!TextInjector.canInject()) return 0
        var count = 0
        while (items.isNotEmpty() && count < MAX) {
            val next = items.removeAt(0)
            TextInjector.commit(next)
            count++
        }
        if (count > 0) persist()
        return count
    }

    private fun restore() {
        if (restored) return
        restored = true
        val ctx = appContext ?: return
        val raw = ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).getString(KEY_ITEMS, null)
            ?: return
        try {
            val array = JSONArray(raw)
            for (i in 0 until array.length()) {
                val value = array.optString(i, "")
                if (value.isNotEmpty()) items.add(value)
            }
        } catch (ignored: Throwable) {
        }
    }

    private fun persist() {
        val ctx = appContext ?: return
        val array = JSONArray()
        items.forEach { array.put(it) }
        ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ITEMS, array.toString())
            .apply()
    }
}
