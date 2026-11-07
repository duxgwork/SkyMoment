package com.example.skymoment.framework

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.LocaleList
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

/**
 * 提供有效语言资源上下文和统一窗口边距处理
 */
abstract class LocalizedActivity : AppCompatActivity() {
    /*
     * 在系统恢复 Decor 前关闭自动避让，避免重建后重复消费窗口边距。
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        // 在复用的 Window 恢复 Decor 前关闭系统避让，防止 Android 15 重建后再次消费边距。
        WindowCompat.setDecorFitsSystemWindows(window, false)
        // 先完成 AppCompat 的页面初始化。
        super.onCreate(savedInstanceState)
    }

    /*
     * 根据系统首选语言创建资源上下文，避免设备默认 Locale 被页面语言污染。
     */
    override fun attachBaseContext(newBase: Context) {
        // 从系统配置读取，避免 Activity 的有效语言改变进程 Locale 默认值后丢失原始偏好。
        val locales = Resources.getSystem().configuration.locales
        // 将包装后的语言上下文交给 Activity 基类。
        super.attachBaseContext(
            // 创建独立语言配置，避免修改全局资源。
            LocaleContext.wrap(
                // 沿用系统传入的基础上下文。
                newBase,
                // 按系统偏好顺序传递语言列表。
                (0 until locales.size()).map { locales[it] })
        )
    }

    // 暴露当前页面生效的语言配置。
    protected val uiLanguage: UiLanguage
        /*
         * 依据当前页面资源中的首选语言解析展示规则。
         */
        get() = LocaleResolver.resolve(listOf(resources.configuration.locales[0]))

    /*
     * 统一透明系统栏、图标明暗与安全区边距，并以初始 padding 防止重复叠加。
     */
    protected fun applyWindowInsets(root: View, lightSystemBars: Boolean = false) {
        // 背景铺到系统栏下方，内容只在这里避让一次，兼容未强制沉浸式的旧系统。
        WindowCompat.enableEdgeToEdge(window)
        // 旧系统不支持浅色导航栏图标时采用兼容配色。
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            // Android 7 的导航按钮只有白色，保留深色底以保证可见。
            @Suppress("DEPRECATION")
            // 使用深色导航栏背景，确保旧版白色导航图标可见。
            window.navigationBarColor = Color.rgb(41, 42, 53)
        }
        // 通过兼容控制器统一设置系统栏图标明暗。
        WindowCompat.getInsetsController(window, root).apply {
            // 浅背景使用深色状态栏图标。
            isAppearanceLightStatusBars = lightSystemBars
            // 浅背景使用深色导航栏图标，平台支持时生效。
            isAppearanceLightNavigationBars = lightSystemBars
        }
        // 保存初始左边距，避免重复分发时累加。
        val left = root.paddingLeft
        // 保存初始顶部边距，后续再叠加系统栏高度。
        val top = root.paddingTop
        // 保存初始右边距，供横屏与切口避让。
        val right = root.paddingRight
        // 保存初始底部边距，供导航栏及键盘避让。
        val bottom = root.paddingBottom
        // 随系统栏、切口或键盘变化重新计算页面安全边距。
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            // 计算当前需要避让区域的合并边距。
            val bars = insets.getInsets(
                // 同时考虑系统栏及显示切口占用区域。
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or
                        // 将输入法高度纳入底部避让范围。
                        WindowInsetsCompat.Type.ime(),
            )
            // 在初始布局边距基础上应用本次系统边距。
            view.setPadding(
                // 保留页面原左边距并避开左侧系统区域。
                left + bars.left,
                // 保留页面原上边距并避开状态栏或切口。
                top + bars.top,
                // 保留页面原右边距并避开右侧系统区域。
                right + bars.right,
                // 保留页面原下边距并避开导航栏或键盘。
                bottom + bars.bottom
            )
            // 继续传递 insets，允许子视图按需处理。
            insets
        }
        // 主动请求首次边距分发，避免初次布局被系统栏遮挡。
        ViewCompat.requestApplyInsets(root)
    }
}

/**
 * 根据有效语言复制资源上下文，供页面与测试一致地创建本地化视图。
 */
object LocaleContext {
    /*
     * app 的 bundle.language.enableSplit=false 固定打包三语资源，离线切换无需下载。
     * 为单个页面创建隔离的语言资源上下文。
     */
    @SuppressLint("AppBundleLocaleChanges")
    fun wrap(base: Context, preferredLocales: List<java.util.Locale>): Context {
        // 按受支持语言规则选择页面实际语言。
        val effective = LocaleResolver.resolve(preferredLocales)
        // 复制原资源配置，避免污染应用全局语言。
        val configuration = Configuration(base.resources.configuration)
        // 将当前页面资源限定为所选界面语言。
        configuration.setLocales(LocaleList(effective.locale))
        // 返回带独立 Configuration 的 Context。
        return base.createConfigurationContext(configuration)
    }
}
