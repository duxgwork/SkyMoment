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

/** Binding 和订阅只属于当前视图，页面自行选择 ViewModel 的作用域。 */
abstract class BaseFragment<VB : ViewBinding>(
    private val inflateBinding: (LayoutInflater, ViewGroup?, Boolean) -> VB,
) : Fragment() {
    private var viewBinding: VB? = null

    protected val binding: VB
        get() = checkNotNull(viewBinding) { "ViewBinding is only available during the Fragment view lifecycle" }

    protected open val observationState: Lifecycle.State = Lifecycle.State.STARTED

    final override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflateBinding(inflater, container, false).also { viewBinding = it }.root

    final override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // 到这里 viewLifecycleOwner 才可安全访问。
        initBinding(binding, savedInstanceState)
        require(observationState == Lifecycle.State.STARTED || observationState == Lifecycle.State.RESUMED)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(observationState) { observeData() }
        }
    }

    final override fun onDestroyView() {
        try {
            if (viewBinding != null) clearBinding()
        } finally {
            viewBinding = null
            super.onDestroyView()
        }
    }

    protected abstract fun initBinding(binding: VB, savedInstanceState: Bundle?)
    protected open suspend fun CoroutineScope.observeData() = Unit

    /** 每次视图销毁都执行；此时 binding 有效，用于释放适配器、监听器和 Renderer。 */
    protected open fun clearBinding() = Unit
}
