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
import com.example.skymoment.domain.SearchDependencies
import com.example.skymoment.framework.BaseActivity
import com.example.skymoment.search.databinding.ActivitySearchBinding
import kotlinx.coroutines.CoroutineScope

/**
 * 独立搜索页面入口，负责依赖装配和路由执行。
 * 将独立搜索页注册到统一路由地址。
 */
@Route(path = Routes.SEARCH)
class SearchActivity : BaseActivity<ActivitySearchBinding>(ActivitySearchBinding::inflate) {
    // 持有本次搜索视图的渲染和交互绑定。
    private lateinit var renderer: SearchRenderer

    private val viewModel: SearchViewModel by lazy {
        // 通过自定义工厂注入依赖，配置重建时保留搜索状态。
        ViewModelProvider(this, createViewModelFactory())[SearchViewModel::class.java]
    }

    /*
     * 页面选择自己的 ViewModel 作用域，并通过工厂注入业务依赖。
     */
    private fun createViewModelFactory(): ViewModelProvider.Factory {
        // 从宿主共享服务读取领域依赖集合。
        val dependencies =
            ServiceLocator.get(ServiceRoutes.APPLICATION, SearchDependencies::class.java)
        // 创建与页面作用域关联的 ViewModel 工厂。
        return viewModelFactory {
            // 声明搜索 ViewModel 的实例化方式。
            initializer {
                // 注入地点查询、会话和搜索历史能力。
                SearchViewModel(
                    dependencies.locations,
                    dependencies.sessions,
                    dependencies.searchHistory
                )
            }
        }
    }

    /*
     * 绑定当前视图的展示与交互回调，每次视图创建时重新执行。
     */
    override fun initBinding(binding: ActivitySearchBinding, savedInstanceState: Bundle?) {
        // 为当前布局创建搜索渲染器。
        renderer = SearchRenderer(
            // 传入类型安全的搜索页控件集合。
            views = binding,
            // 恢复当前输入文本，避免重建后丢失查询。
            initialQuery = viewModel.state.value.query,
            // 输入变化交给状态机按当前语言执行防抖查询。
            queryChanged = { viewModel.queryChanged(it, uiLanguage.locale.toLanguageTag()) },
            // 键盘搜索操作交给状态机立即提交。
            submit = viewModel::submit,
            // 候选点击先由状态机处理会话选择。
            selectLocation = { location ->
                // 只有首次有效选择返回 token 时才交付页面结果。
                viewModel.selectLocation(location)?.let(::deliverSelection)
            },
            // 点击历史词使用相同的搜索入口。
            selectHistory = viewModel::selectHistory,
            // 清空历史操作交给历史仓储和状态机。
            clearHistory = viewModel::clearHistory,
            // 返回操作遵循 Activity 的统一返回分发。
            back = { onBackPressedDispatcher.onBackPressed() },
            // 失败重试由状态机决定请求代次和去重策略。
            retry = viewModel::retry,
        )
    }

    /*
     * 首页选择模式直接回传结果；独立路由访问仍通过首页路由展示选中的地点。
     */
    private fun deliverSelection(token: String) {
        // 首页发起的选择模式通过 Activity Result 回传。
        if (intent.getBooleanExtra(LocationSearchContract.RETURN_SELECTION, false)) {
            // 只返回随机 token，不把地点对象写进 Intent。
            setResult(RESULT_OK, Intent().putExtra(NavigationExtras.SESSION_TOKEN, token))
            // 结果交付后结束搜索页，回到原首页。
            finish()
        } else {
            // 携带新会话打开或复用首页。
            ARouterNavigator.home(this, token)
        }
    }

    /*
     * 在可见生命周期内收集状态；停止收集时不销毁已有 ViewModel 数据。
     */
    override suspend fun CoroutineScope.observeData() {
        // 持续把搜索状态快照交给渲染器。
        viewModel.state.collect(renderer::render)
    }

    /*
     * 页面进入前台时转发语言和刷新事件，复用已有的 ViewModel。
     */
    override fun onStart() {
        super.onStart()
        // 更新当前语言并解除上一轮选择防重入标志。
        viewModel.onForeground(uiLanguage.locale.toLanguageTag())
    }

    /*
     * 解除当前视图关联的监听、适配器或渲染器引用。
     */
    override fun clearBinding() {
        // 页面销毁时解除渲染器中的监听和适配器引用。
        renderer.clear()
    }
}
