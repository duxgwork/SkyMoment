package com.example.skymoment.framework

import android.os.Bundle
import android.view.LayoutInflater
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.viewbinding.ViewBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 页面只声明 Binding；ViewModel 的数量、作用域及工厂由页面决定。
 * 继承统一语言和系统栏处理能力。
 */
abstract class BaseActivity<VB : ViewBinding>(
    // 由具体页面提供 Binding 工厂，避免泛型反射创建视图。
    private val inflateBinding: (LayoutInflater) -> VB,
) : LocalizedActivity() {
    // 只在 Activity 视图生命周期内持有 Binding。
    private var viewBinding: VB? = null

    // 向子页面提供受生命周期约束的 Binding 入口。
    protected val binding: VB
        /*
         * 视图未创建或已释放时立即暴露不合法访问。
         */
        get() = checkNotNull(viewBinding) { "ViewBinding is only available during the Activity lifecycle" }

    // 默认在页面可见时订阅，子类可收紧到 RESUMED。
    protected open val observationState: Lifecycle.State = Lifecycle.State.STARTED

    // 默认浅色系统栏背景配合深色系统图标。
    protected open val lightSystemBars: Boolean = true

    /*
     * 固定创建顺序，避免子类漏掉通用视图初始化。
     */
    final override fun onCreate(savedInstanceState: Bundle?) {
        // 先初始化系统及本地化 Activity 状态。
        super.onCreate(savedInstanceState)
        // 用页面提供的工厂创建当前布局 Binding。
        viewBinding = inflateBinding(layoutInflater)
        // 把 Binding 根视图设为 Activity 内容。
        setContentView(binding.root)
        // 在根视图上应用状态栏、导航栏和键盘避让。
        applyWindowInsets(binding.root, lightSystemBars)
        // 把当前布局和恢复状态交给子页面初始化。
        initBinding(binding, savedInstanceState)
        // 限制订阅阶段为可见或可交互，避免错误生命周期配置。
        require(observationState == Lifecycle.State.STARTED || observationState == Lifecycle.State.RESUMED)
        // 在 Activity 生命周期作用域启动数据收集管理。
        lifecycleScope.launch {
            // 达到目标状态时订阅，离开时取消，恢复后重新订阅。
            repeatOnLifecycle(observationState) { observeData() }
        }
    }

    /*
     * 固定销毁入口以保证 Binding 必定释放。
     */
    final override fun onDestroy() {
        try {
            // 仅在视图存在时移除页面监听和适配器引用。
            if (viewBinding != null) clearBinding()
        } finally {
            // 清空 Binding，防止销毁后访问旧页面。
            viewBinding = null
            // 完成 Activity 的系统销毁流程。
            super.onDestroy()
        }
    }

    /*
     * 要求每个具体页面实现布局与交互初始化。
     */
    protected abstract fun initBinding(binding: VB, savedInstanceState: Bundle?)

    /*
     * 仅订阅状态，一次性请求由 ViewModel 或明确的页面事件驱动。
     */
    protected open suspend fun CoroutineScope.observeData() = Unit

    /*
     * 解除监听和渲染器引用，此时 binding 仍然有效。
     */
    protected open fun clearBinding() = Unit
}
