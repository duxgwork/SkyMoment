package com.example.skymoment.weather

import android.os.Bundle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.skymoment.core.router.NavigationExtras
import com.example.skymoment.core.router.ServiceLocator
import com.example.skymoment.core.router.ServiceRoutes
import com.example.skymoment.domain.WeatherDisplayDependencies
import com.example.skymoment.framework.BaseFragment
import com.example.skymoment.framework.LocaleResolver
import com.example.skymoment.weather.databinding.FragmentWeatherBinding
import kotlinx.coroutines.CoroutineScope

/**
 * 声明天气页 Binding、ViewModel 和生命周期行为，视图创建与销毁由基类负责。
 */
class WeatherFragment : BaseFragment<FragmentWeatherBinding>(FragmentWeatherBinding::inflate) {

    // 渲染器只随当前视图存在，销毁视图后置空。
    private var renderer: WeatherRenderer? = null

    /*
     * 从当前资源解析页面实际使用的语言。
     */
    private val language get() = LocaleResolver.resolve(listOf(resources.configuration.locales[0]))


    private val viewModel: WeatherViewModel by lazy {
        // 通过注入工厂创建或复用 ViewModel，视图重建不重取实例。
        ViewModelProvider(this, createViewModelFactory())[WeatherViewModel::class.java]
    }

    /*
     * 页面选择自己的 ViewModel 作用域，并通过工厂注入业务依赖。
     */
    private fun createViewModelFactory(): ViewModelProvider.Factory {
        // 通过宿主服务取得共享天气、地点和会话依赖。
        val dependencies =
            ServiceLocator.get(ServiceRoutes.APPLICATION, WeatherDisplayDependencies::class.java)
        // 创建当前 Fragment 的 ViewModel 工厂。
        return viewModelFactory {
            // 声明天气状态机的实例化过程。
            initializer {
                // 用领域依赖和当前会话构造天气状态机。
                WeatherViewModel(
                    // 注入天气查询和有效会话解析能力。
                    dependencies.weather,
                    dependencies.sessions,
                    // 读取唯一会话参数，并注入独立名称刷新能力。
                    arguments?.getString(NavigationExtras.SESSION_TOKEN).orEmpty(),
                    dependencies.locations
                )
            }
        }
    }

    /*
     * 绑定当前视图的展示与交互回调，每次视图创建时重新执行。
     */
    override fun initBinding(binding: FragmentWeatherBinding, savedInstanceState: Bundle?) {
        // 为本次视图创建天气渲染器。
        renderer = WeatherRenderer(
            // 绑定当前 Fragment 的布局控件。
            binding,
            // 使用页面语言进行数值与日期格式化。
            language.locale,
            // 当前天气错误只触发当前区域重试。
            viewModel::retryCurrent,
            // 七日预报错误只触发日预报重试。
            viewModel::retryForecast,
            // 小时预报错误只触发小时区域重试。
            viewModel::retryHourly,
            // 全空态的恢复操作允许刷新三个天气区域。
            retryAll = viewModel::refresh
        )
        // 先渲染已持有的快照，减少重建时内容闪空。
        renderer?.render(viewModel.state.value)
    }

    /*
     * 在可见生命周期内收集状态；停止收集时不销毁已有 ViewModel 数据。
     */
    override suspend fun CoroutineScope.observeData() {
        // 随视图生命周期把最新状态交给当前渲染器。
        viewModel.state.collect { renderer?.render(it) }
    }

    /*
     * 页面进入前台时转发语言和刷新事件，复用已有的 ViewModel。
     */
    override fun onStart() {
        super.onStart()
        // 告知状态机前台恢复，并按当前语言判断刷新。
        viewModel.onForeground(language.locale.toLanguageTag())
    }

    /*
     * 页面离开前台时暂停对应的定位或定时任务。
     */
    override fun onStop() {
        // 退后台时停止前台边界与冷却计时。
        viewModel.onBackground()
        super.onStop()
    }

    /*
     * 解除当前视图关联的监听、适配器或渲染器引用。
     */
    override fun clearBinding() {
        // 解绑当前视图的适配器及操作回调。
        renderer?.clear()
        // 释放渲染器引用，避免持有已销毁视图。
        renderer = null
    }

    /**
     * 集中提供带会话参数的页面创建入口。
     */
    companion object {
        /*
         * 仅使用随机会话 token 构造 Fragment 参数，不传递地点对象。
         */
        fun forSession(token: String) = WeatherFragment().apply {
            // 只把随机 token 放进 Fragment 参数，不携带地点数据。
            arguments = Bundle().apply { putString(NavigationExtras.SESSION_TOKEN, token) }
        }
    }
}
