package com.tvboard.ime.ime

import android.view.View
import kotlin.math.abs

/**
 * D-pad 焦点寻址。
 *
 * 不使用 nextFocusXxx 属性：键盘布局是动态生成（切页/切语言/候选栏显隐），
 * XML 静态邻接关系无法覆盖。几何寻址对任意布局自适应。
 *
 * 打分：主轴距离 + 交叉轴偏移 × 2.5。交叉轴惩罚是必要的——否则从 "p" 按 ↓
 * 会斜跳到 ⌫ 而不是到 "l"。
 */
object FocusNavigator {

    enum class Dir { LEFT, RIGHT, UP, DOWN }

    fun next(current: View, all: List<View>, dir: Dir): View? {
        val cx = (current.left + current.right) / 2
        val cy = (current.top + current.bottom) / 2

        var best: View? = null
        var bestScore = Float.MAX_VALUE

        for (candidate in all) {
            if (candidate === current) continue
            if (!candidate.isShown || !candidate.isEnabled || !candidate.isFocusable) continue

            val dx = ((candidate.left + candidate.right) / 2 - cx).toFloat()
            val dy = ((candidate.top + candidate.bottom) / 2 - cy).toFloat()

            val primary = when (dir) {
                Dir.LEFT -> -dx
                Dir.RIGHT -> dx
                Dir.UP -> -dy
                Dir.DOWN -> dy
            }
            // 必须在目标方向上，否则会横向"吸收"同行的点
            if (primary <= 0f) continue

            val cross = if (dir == Dir.LEFT || dir == Dir.RIGHT) abs(dy) else abs(dx)
            val score = primary + cross * 2.5f
            if (score < bestScore) {
                bestScore = score
                best = candidate
            }
        }
        return best
    }
}
