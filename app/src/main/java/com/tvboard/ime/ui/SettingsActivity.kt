package com.tvboard.ime.ui

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.tvboard.ime.BuildConfig
import com.tvboard.ime.R
import com.tvboard.ime.util.dp

/**
 * 启用引导 + 简易测试区。
 *
 * TV 上第三方输入法的启用入口常被 ROM 藏起，而且**是两层结构**：
 * 系统输入法设置页（KeyboardActivity）只列出「已启用」的键盘，
 * 勾选入口在第二层 Manage keyboards（InputMethodManager.getInputMethodList）。
 * 因此这里既给出准确的两层路径，也实时显示本应用当前的启用/选中状态，
 * 避免用户在第一层找不到自己而误判为安装失败。
 */
class SettingsActivity : Activity() {

    private lateinit var statusView: TextView
    private lateinit var step1Hint: TextView
    private lateinit var enableButton: Button
    private lateinit var testView: EditText

    /** 上一次 onResume 时读到的启用状态，用于识别「刚从系统设置启用完回来」。 */
    private var lastEnabled: Boolean? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(48f), dp(32f), dp(48f), dp(32f))
        }

        column.addView(title(getString(R.string.settings_title), 26f))
        column.addView(
            body(
                getString(R.string.settings_about_body),
                15f,
                R.color.text_secondary
            )
        )

        statusView = body("", 16f, R.color.text_primary).apply {
            setPadding(0, dp(16f), 0, 0)
        }
        column.addView(statusView)

        column.addView(section(getString(R.string.settings_step1)))
        column.addView(body(getString(step1DescRes()), 14f, R.color.text_secondary))
        // 拿到 WRITE_SECURE_SETTINGS 时（adb 授权过的开发/调试设备）直接自己写设置，
        // 免去在系统设置里点三层；没有授权就只显示手工路径，不干扰普通用户。
        if (canSelfEnable()) {
            column.addView(
                actionButton(getString(R.string.settings_enable_self)) {
                    enableAndSelectSelf()
                }
            )
        }
        enableButton = actionButton(getString(R.string.settings_enable)) {
            openInputMethodSettings()
        }
        column.addView(enableButton)
        step1Hint = body(getString(R.string.settings_step1_hint), 13f, R.color.status_warn).apply {
            setPadding(0, dp(4f), 0, dp(8f))
        }
        column.addView(step1Hint)

        column.addView(section(getString(R.string.settings_step2)))
        column.addView(body(getString(R.string.settings_step2_desc), 14f, R.color.text_secondary))
        column.addView(
            actionButton(getString(R.string.settings_pick)) {
                val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.showInputMethodPicker()
            }
        )

        column.addView(section(getString(R.string.settings_test)))
        val test = EditText(this).apply {
            hint = getString(R.string.settings_test_hint)
            inputType = InputType.TYPE_CLASS_TEXT
            textSize = 18f
            setSingleLine(false)
            minLines = 2
            setPadding(dp(16f), dp(12f), dp(16f), dp(12f))
        }
        testView = test
        column.addView(
            test,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8f) }
        )

        column.addView(
            body("版本 " + BuildConfig.VERSION_NAME, 13f, R.color.text_secondary)
        )

        val scroll = ScrollView(this).apply {
            addView(column)
            isFillViewport = true
        }
        setContentView(scroll)

        // 未启用时把焦点留给「打开输入法设置」：此时自动聚焦测试框只会弹出系统自带键盘，
        // 既验证不了本输入法，又容易让人以为装错了。启用后再聚焦测试框。
        if (isEnabled()) {
            test.post { test.requestFocus() }
        } else {
            enableButton.requestFocus()
        }
    }

    /**
     * 系统输入法设置页是不是两层的。
     *
     * AOSP TV 的 KeyboardActivity 只列「已启用」的键盘，勾选入口在第二层 Manage keyboards；
     * 多数国产电视用的是手机版 Settings，一层就能直接打开开关。文案必须分开写，
     * 否则用户照着错误路径找，只会得出「装了没生效」的结论。
     */
    private val twoLevelSettings: Boolean by lazy {
        resolveImeSettingsPackage() == "com.android.tv.settings"
    }

    private fun step1DescRes(): Int =
        if (twoLevelSettings) R.string.settings_step1_desc_tv else R.string.settings_step1_desc

    /** 解析 ACTION_INPUT_METHOD_SETTINGS 实际落到哪个包，用来判断设置层级。 */
    private fun resolveImeSettingsPackage(): String? = try {
        val intent = Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.resolveActivity(intent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            packageManager.resolveActivity(intent, 0)
        }
        info?.activityInfo?.packageName
    } catch (e: Exception) {
        null
    }

    override fun onResume() {
        super.onResume()
        // 从系统设置返回后状态可能已变，每次回前台重新读一次
        refreshStatus()
        val now = isEnabled()
        // 刚从系统设置里勾选完返回：焦点直接交给测试框，省用户再按几下方向键
        if (lastEnabled == false && now && ::testView.isInitialized) {
            testView.post { testView.requestFocus() }
        }
        lastEnabled = now
    }

    /**
     * 本应用是否已被系统启用。
     *
     * 注意 `ime list` 能看到 ≠ 已启用：前者是 InputMethodInfo 已注册，
     * 后者要求 id 出现在 ENABLED_INPUT_METHODS 里。写该设置需要 WRITE_SECURE_SETTINGS，
     * 普通安装拿不到，只能去系统界面勾选；已用 adb 授权的设备则走「一键启用」。
     */
    private fun isEnabled(): Boolean {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager ?: return false
        return imm.enabledInputMethodList.any { it.packageName == packageName }
    }

    private fun isCurrent(): Boolean {
        val id = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        return id != null && id.startsWith("$packageName/")
    }

    private fun refreshStatus() {
        if (!::statusView.isInitialized) return
        val enabled = isEnabled()
        val current = isCurrent()
        val textRes = when {
            current -> R.string.settings_status_current
            enabled -> R.string.settings_status_enabled
            else -> R.string.settings_status_disabled
        }
        val colorRes = when {
            current -> R.color.status_ok
            enabled -> R.color.text_primary
            else -> R.color.status_warn
        }
        statusView.text = getString(R.string.settings_status, getString(textRes))
        statusView.setTextColor(getColorValue(colorRes))
        // 已启用后就不必再强调「只看到 Gboard 是正常的」；一层式设置本来也没有这句
        if (::step1Hint.isInitialized) {
            step1Hint.visibility = if (enabled || !twoLevelSettings) View.GONE else View.VISIBLE
        }
    }

    /**
     * 是否已经被授予 WRITE_SECURE_SETTINGS。
     *
     * 该权限属于 signature|privileged|development 级，安装时不会自动授予，但可以在调试设备上
     * 由使用者主动执行一次：
     *     adb shell pm grant com.tvboard.ime android.permission.WRITE_SECURE_SETTINGS
     * 有了它才能自己写 ENABLED_INPUT_METHODS，从而做出「一键启用」。
     * 这是唯一不依赖系统设置界面的办法——TV 的勾选页不是独立 Activity，无法用 Intent 直达。
     */
    private fun canSelfEnable(): Boolean =
        androidx.core.content.ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.WRITE_SECURE_SETTINGS
        ) == PackageManager.PERMISSION_GRANTED

    /**
     * 直接把本输入法写进已启用列表并设为默认。
     *
     * 等价于系统设置里勾选 + 选中，也等价于 `adb shell ime enable/set`；
     * 前提是上面那条权限已授权，否则调用不会生效（这里只在按钮可见时才会走到）。
     */
    private fun enableAndSelectSelf() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
        val target = imm?.inputMethodList?.firstOrNull { it.packageName == packageName }?.id
        if (target == null) {
            Toast.makeText(this, R.string.settings_enable_self_fail, Toast.LENGTH_LONG).show()
            return
        }
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_INPUT_METHODS
        ).orEmpty().split(':').filter { it.isNotBlank() }.toMutableSet()
        enabled.add(target)
        Settings.Secure.putString(
            contentResolver,
            Settings.Secure.ENABLED_INPUT_METHODS,
            enabled.joinToString(":")
        )
        Settings.Secure.putString(
            contentResolver,
            Settings.Secure.DEFAULT_INPUT_METHOD,
            target
        )
        refreshStatus()
        Toast.makeText(this, R.string.settings_enable_self_done, Toast.LENGTH_SHORT).show()
    }

    /**
     * 打开系统输入法设置。
     *
     * 这个入口在 TV 上通常是两层：第一层只列已启用键盘，勾选在
     * 「Manage keyboards / 管理键盘」里。若 ROM 没导出该页面则退回设置首页，
     * 至少给用户一个能自己找的起点，而不是点了没反应。
     */
    private fun openInputMethodSettings() {
        val direct = Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)
        if (direct.resolveActivity(packageManager) != null) {
            startActivitySafely(direct)
            return
        }
        startActivitySafely(Intent(Settings.ACTION_SETTINGS))
    }

    private fun title(text: String, size: Float): TextView = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(getColorValue(R.color.text_primary))
        setPadding(0, 0, 0, dp(12f))
    }

    private fun section(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 18f
        setTextColor(getColorValue(R.color.text_primary))
        setPadding(0, dp(24f), 0, dp(6f))
    }

    private fun body(text: String, size: Float, colorRes: Int): TextView = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(getColorValue(colorRes))
        setPadding(0, 0, 0, dp(8f))
    }

    private fun actionButton(text: String, onClick: () -> Unit): Button =
        Button(this, null, 0, R.style.Widget_TvBoard_Button).apply {
            this.text = text
            gravity = Gravity.CENTER
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4f); bottomMargin = dp(4f) }
        }

    private fun getColorValue(resId: Int): Int =
        androidx.core.content.ContextCompat.getColor(this, resId)

    private fun startActivitySafely(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            // 部分 ROM 未导出该设置页。给出明确提示，不要让按钮静默失效——
            // 之前正是「点了没反应」让人误以为输入法没装上。
            android.widget.Toast.makeText(
                this,
                "该系统未提供此设置页，请手动进入 设置 → 设备偏好设置 → 键盘",
                android.widget.Toast.LENGTH_LONG
            ).show()
        }
    }
}
