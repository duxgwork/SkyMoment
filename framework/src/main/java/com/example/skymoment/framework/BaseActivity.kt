package com.example.skymoment.framework

import android.os.Bundle
import android.view.LayoutInflater
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.viewbinding.ViewBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** 页面只声明 Binding；ViewModel 的数量、作用域及工厂由页面决定。 */
abstract class BaseActivity<VB : ViewBinding>(
    private val inflateBinding: (LayoutInflater) -> VB,
) : LocalizedActivity() {
    private var viewBinding: VB? = null

    protected val binding: VB
        get() = checkNotNull(viewBinding) { "ViewBinding is only available during the Activity lifecycle" }

    protected open val observationState: Lifecycle.State = Lifecycle.State.STARTED
    protected open val lightSystemBars: Boolean = true

    final override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewBinding = inflateBinding(layoutInflater)
        setContentView(binding.root)
        applyWindowInsets(binding.root, lightSystemBars)
        initBinding(binding, savedInstanceState)
        require(observationState == Lifecycle.State.STARTED || observationState == Lifecycle.State.RESUMED)
        lifecycleScope.launch {
            repeatOnLifecycle(observationState) { observeData() }
        }
    }

    final override fun onDestroy() {
        try {
            if (viewBinding != null) clearBinding()
        } finally {
            viewBinding = null
            super.onDestroy()
        }
    }

    protected abstract fun initBinding(binding: VB, savedInstanceState: Bundle?)

    /** 仅订阅状态，一次性请求由 ViewModel 或明确的页面事件驱动。 */
    protected open suspend fun CoroutineScope.observeData() = Unit

    /** 解除监听和渲染器引用，此时 binding 仍然有效。 */
    protected open fun clearBinding() = Unit
}
