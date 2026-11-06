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
 **/
abstract class LocalizedActivity : AppCompatActivity() {
    // 在系统恢复 Decor 前关闭自动避让，避免重建后重复消费窗口边距。
    override fun onCreate(savedInstanceState: Bundle?) {
        // 在复用的 Window 恢复 Decor 前关闭系统避让，防止 Android 15 重建后再次消费边距。
        WindowCompat.setDecorFitsSystemWindows(window, false)
        super.onCreate(savedInstanceState)
    }

    // 根据系统首选语言创建资源上下文，避免设备默认 Locale 被页面语言污染。
    override fun attachBaseContext(newBase: Context) {
        // 从系统配置读取，避免 Activity 的有效语言改变进程 Locale 默认值后丢失原始偏好。
        val locales = Resources.getSystem().configuration.locales
        super.attachBaseContext(
            LocaleContext.wrap(
                newBase,
                (0 until locales.size()).map { locales[it] })
        )
    }

    protected val uiLanguage: UiLanguage
        get() = LocaleResolver.resolve(listOf(resources.configuration.locales[0]))

    // 统一透明系统栏、图标明暗与安全区边距，并以初始 padding 防止重复叠加。
    protected fun applyWindowInsets(root: View, lightSystemBars: Boolean = false) {
        // 背景铺到系统栏下方，内容只在这里避让一次，兼容未强制沉浸式的旧系统。
        WindowCompat.enableEdgeToEdge(window)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            // Android 7 的导航按钮只有白色，保留深色底以保证可见。
            @Suppress("DEPRECATION")
            window.navigationBarColor = Color.rgb(41, 42, 53)
        }
        WindowCompat.getInsetsController(window, root).apply {
            isAppearanceLightStatusBars = lightSystemBars
            isAppearanceLightNavigationBars = lightSystemBars
        }
        val left = root.paddingLeft
        val top = root.paddingTop
        val right = root.paddingRight
        val bottom = root.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or
                        WindowInsetsCompat.Type.ime(),
            )
            view.setPadding(
                left + bars.left,
                top + bars.top,
                right + bars.right,
                bottom + bars.bottom
            )
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }
}

/** 根据有效语言复制资源上下文，供页面与测试一致地创建本地化视图。 */
object LocaleContext {
    // app 的 bundle.language.enableSplit=false 固定打包三语资源，离线切换无需下载。
    @SuppressLint("AppBundleLocaleChanges")
    fun wrap(base: Context, preferredLocales: List<java.util.Locale>): Context {
        val effective = LocaleResolver.resolve(preferredLocales)
        val configuration = Configuration(base.resources.configuration)
        configuration.setLocales(LocaleList(effective.locale))
        return base.createConfigurationContext(configuration)
    }
}
