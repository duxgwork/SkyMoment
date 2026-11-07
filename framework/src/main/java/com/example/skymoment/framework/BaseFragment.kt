package com.example.skymoment.framework

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.viewbinding.ViewBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Binding 和订阅只属于当前视图，页面自行选择 ViewModel 的作用域。
 * 提供 Fragment 通用视图生命周期管理。
 */
abstract class BaseFragment<VB : ViewBinding>(
    // 接收明确的 Binding 工厂，避免运行时解析泛型类型。
    private val inflateBinding: (LayoutInflater, ViewGroup?, Boolean) -> VB,
) : Fragment() {
    // Binding 的生命周期随当前视图，不随整个 Fragment 实例。
    private var viewBinding: VB? = null

    // 提供只在视图有效期内可访问的 Binding。
    protected val binding: VB
        /*
         * 视图销毁后访问 Binding 会立即报错。
         */
        get() = checkNotNull(viewBinding) { "ViewBinding is only available during the Fragment view lifecycle" }

    // 默认仅在 Fragment 视图可见时订阅状态。
    protected open val observationState: Lifecycle.State = Lifecycle.State.STARTED

    /*
     * 统一创建视图并保存其 Binding。
     * 生成未提前附着的根视图，并保存这次 Binding。
     */
    final override fun onCreateView(
        // 使用宿主提供的布局加载器。
        inflater: LayoutInflater,
        // 接收可能为空的父容器，用于生成正确布局参数。
        container: ViewGroup?,
        // 接收当前视图的恢复状态。
        savedInstanceState: Bundle?,
    ): View = inflateBinding(inflater, container, false).also { viewBinding = it }.root

    /*
     * 视图创建后执行通用初始化和订阅配置。
     */
    final override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        // 先完成 Fragment 的标准视图创建回调。
        super.onViewCreated(view, savedInstanceState)
        // 到这里 viewLifecycleOwner 才可安全访问。
        initBinding(binding, savedInstanceState)
        // 只允许在 STARTED 或 RESUMED 阶段收集页面数据。
        require(observationState == Lifecycle.State.STARTED || observationState == Lifecycle.State.RESUMED)
        // 使用视图生命周期作用域，避免重建后继续更新旧视图。
        viewLifecycleOwner.lifecycleScope.launch {
            // 随视图可见性自动取消与重启收集任务。
            viewLifecycleOwner.repeatOnLifecycle(observationState) { observeData() }
        }
    }

    /*
     * 视图销毁时统一清理页面资源。
     */
    final override fun onDestroyView() {
        try {
            // 视图尚存在时调用具体页面的清理逻辑。
            if (viewBinding != null) clearBinding()
        } finally {
            // 释放旧视图 Binding，允许下次创建新 Binding。
            viewBinding = null
            // 完成 Fragment 视图销毁的系统处理。
            super.onDestroyView()
        }
    }

    /*
     * 要求具体 Fragment 实现本次布局初始化。
     */
    protected abstract fun initBinding(binding: VB, savedInstanceState: Bundle?)

    /*
     * 默认没有数据订阅，具体页面可按需覆盖。
     */
    protected open suspend fun CoroutineScope.observeData() = Unit

    /*
     * 每次视图销毁都执行；此时 binding 有效，用于释放适配器、监听器和 Renderer。
     */
    protected open fun clearBinding() = Unit
}
