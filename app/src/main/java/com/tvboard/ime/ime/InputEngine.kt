package com.tvboard.ime.ime

import android.content.Context
import com.tvboard.ime.data.DictRepository

/** 输入引擎。英文不产生候选，中文走词库前缀查询。 */
class InputEngine(context: Context) {

    private val dict = DictRepository(context.applicationContext)

    fun query(composing: String): List<String> {
        if (composing.isEmpty()) return emptyList()
        if (!composing[0].isLetter()) return emptyList()
        return dict.candidates(composing, 6)
    }
}
