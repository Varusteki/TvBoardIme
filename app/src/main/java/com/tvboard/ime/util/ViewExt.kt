package com.tvboard.ime.util

import android.content.Context
import android.view.View

/** dp → px。API 21 上没有 View.dp 扩展，统一放这里，避免各处散落 density 换算 */
fun Context.dp(value: Float): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

fun View.dp(value: Float): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

fun Context.sp(value: Float): Float = value * resources.displayMetrics.scaledDensity
