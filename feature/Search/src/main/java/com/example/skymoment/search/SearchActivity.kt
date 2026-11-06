package com.example.skymoment.search

import android.content.Intent
import android.os.Bundle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.alibaba.android.arouter.facade.annotation.Route
import com.example.skymoment.core.router.ARouterNavigator
import com.example.skymoment.core.router.LocationSearchContract
import com.example.skymoment.core.router.NavigationExtras
import com.example.skymoment.core.router.Routes
import com.example.skymoment.core.router.ServiceLocator
import com.example.skymoment.core.router.ServiceRoutes
import com.example.skymoment.domain.WeatherDependencies
import com.example.skymoment.framework.BaseActivity
import com.example.skymoment.search.databinding.ActivitySearchBinding
import kotlinx.coroutines.CoroutineScope

@Route(path = Routes.SEARCH)
/** 独立搜索页面入口，负责依赖装配和路由执行。 */
class SearchActivity : BaseActivity<ActivitySearchBinding>(ActivitySearchBinding::inflate) {
    private val viewModel: SearchViewModel by lazy(LazyThreadSafetyMode.NONE) {
        ViewModelProvider(this, createViewModelFactory())[SearchViewModel::class.java]
    }
    private lateinit var renderer: SearchRenderer

    // 页面选择自己的 ViewModel 作用域，并通过工厂注入业务依赖。
    private fun createViewModelFactory(): ViewModelProvider.Factory {
        val dependencies = ServiceLocator.get(ServiceRoutes.APPLICATION, WeatherDependencies::class.java)
        return viewModelFactory {
            initializer {
                SearchViewModel(dependencies.locations, dependencies.sessions, dependencies.searchHistory)
            }
        }
    }

    // 绑定当前视图的展示与交互回调，每次视图创建时重新执行。
    override fun initBinding(binding: ActivitySearchBinding, savedInstanceState: Bundle?) {
        renderer = SearchRenderer(
            views = binding,
            initialQuery = viewModel.state.value.query,
            queryChanged = { viewModel.queryChanged(it, uiLanguage.locale.toLanguageTag()) },
            submit = viewModel::submit,
            selectLocation = { location ->
                viewModel.select(location)?.let(::deliverSelection)
            },
            selectHistory = viewModel::selectHistory,
            clearHistory = viewModel::clearHistory,
            back = { onBackPressedDispatcher.onBackPressed() },
            retry = viewModel::retry,
        )
    }

    // 首页选择模式直接回传结果；独立路由访问仍通过首页路由展示选中的地点。
    private fun deliverSelection(token: String) {
        if (intent.getBooleanExtra(LocationSearchContract.RETURN_SELECTION, false)) {
            setResult(RESULT_OK, Intent().putExtra(NavigationExtras.SESSION_TOKEN, token))
            finish()
        } else {
            ARouterNavigator.home(this, token)
        }
    }

    // 在可见生命周期内收集状态；停止收集时不销毁已有 ViewModel 数据。
    override suspend fun CoroutineScope.observeData() {
        viewModel.state.collect(renderer::render)
    }

    // 页面进入前台时转发语言和刷新事件，复用已有的 ViewModel。
    override fun onStart() {
        super.onStart()
        viewModel.onForeground(uiLanguage.locale.toLanguageTag())
    }

    // 解除当前视图关联的监听、适配器或渲染器引用。
    override fun clearBinding() {
        renderer.clear()
    }
}
