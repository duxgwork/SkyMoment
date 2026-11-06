package com.example.skymoment.weather

import android.os.Bundle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.skymoment.core.router.NavigationExtras
import com.example.skymoment.core.router.ServiceLocator
import com.example.skymoment.core.router.ServiceRoutes
import com.example.skymoment.domain.WeatherDependencies
import com.example.skymoment.framework.BaseFragment
import com.example.skymoment.framework.LocaleResolver
import com.example.skymoment.weather.databinding.FragmentWeatherBinding
import kotlinx.coroutines.CoroutineScope

/** 声明天气页 Binding、ViewModel 和生命周期行为，视图创建与销毁由基类负责。 */
class WeatherFragment : BaseFragment<FragmentWeatherBinding>(FragmentWeatherBinding::inflate) {
    private val viewModel: WeatherViewModel by lazy(LazyThreadSafetyMode.NONE) {
        ViewModelProvider(this, createViewModelFactory())[WeatherViewModel::class.java]
    }
    private var renderer: WeatherRenderer? = null
    private val language get() = LocaleResolver.resolve(listOf(resources.configuration.locales[0]))
    // 页面选择自己的 ViewModel 作用域，并通过工厂注入业务依赖。
    private fun createViewModelFactory(): ViewModelProvider.Factory {
        val dependencies = ServiceLocator.get(ServiceRoutes.APPLICATION, WeatherDependencies::class.java)
        return viewModelFactory {
            initializer {
                WeatherViewModel(
                    dependencies.weather, dependencies.sessions,
                    arguments?.getString(NavigationExtras.SESSION_TOKEN).orEmpty(), dependencies.locations
                )
            }
        }
    }

    // 绑定当前视图的展示与交互回调，每次视图创建时重新执行。
    override fun initBinding(binding: FragmentWeatherBinding, savedInstanceState: Bundle?) {
        renderer = WeatherRenderer(
            binding,
            language.locale,
            viewModel::retryCurrent,
            viewModel::retryForecast,
            viewModel::retryHourly,
            retryAll = viewModel::refresh
        )
        renderer?.render(viewModel.state.value)
    }

    // 在可见生命周期内收集状态；停止收集时不销毁已有 ViewModel 数据。
    override suspend fun CoroutineScope.observeData() {
        viewModel.state.collect { renderer?.render(it) }
    }

    // 页面进入前台时转发语言和刷新事件，复用已有的 ViewModel。
    override fun onStart() {
        super.onStart()
        viewModel.onForeground(language.locale.toLanguageTag())
    }

    // 页面离开前台时暂停对应的定位或定时任务。
    override fun onStop() {
        viewModel.onBackground()
        super.onStop()
    }

    // 解除当前视图关联的监听、适配器或渲染器引用。
    override fun clearBinding() {
        renderer?.clear()
        renderer = null
    }

    companion object {
        // 仅使用随机会话 token 构造 Fragment 参数，不传递地点对象。
        fun forSession(token: String) = WeatherFragment().apply {
            arguments = Bundle().apply { putString(NavigationExtras.SESSION_TOKEN, token) }
        }
    }
}
