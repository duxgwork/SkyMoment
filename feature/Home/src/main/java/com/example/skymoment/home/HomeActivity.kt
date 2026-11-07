package com.example.skymoment.home

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.alibaba.android.arouter.facade.annotation.Route
import com.example.skymoment.core.router.LocationSearchContract
import com.example.skymoment.core.router.NavigationExtras
import com.example.skymoment.core.router.Routes
import com.example.skymoment.core.router.ServiceLocator
import com.example.skymoment.core.router.ServiceRoutes
import com.example.skymoment.core.service.IWeatherService
import com.example.skymoment.domain.HomeDependencies
import com.example.skymoment.framework.BaseActivity
import com.example.skymoment.home.databinding.ActivityHomeBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 首页展示天气和地点切换入口，接收生命周期并执行 ViewModel 发出的平台操作。
 * 首页通过明确的 Binding 工厂复用本地化与生命周期基础。
 */
@Route(path = Routes.HOME)
class HomeActivity : BaseActivity<ActivityHomeBinding>(ActivityHomeBinding::inflate) {
    // 持有本次页面布局对应的渲染器。
    private lateinit var renderer: HomeRenderer

    // 暂存系统恢复的会话标识，后续交给仓储验证。
    private var restoredSessionToken: String? = null

    // 记录重建前是否正在等待用户选择地点。
    private var restoredChangingLocation = false

    // 仅在页面可交互时渲染 Fragment 和处理一次性事件。
    override val observationState = Lifecycle.State.RESUMED

    // 按需获取宿主共享依赖，避免页面自行创建仓储。
    private val dependencies by lazy {
        // 通过组件服务入口解析应用依赖。
        ServiceLocator.get(
            // 使用宿主提供的应用服务地址。
            ServiceRoutes.APPLICATION,
            // 验证服务符合首页所需的依赖契约。
            HomeDependencies::class.java
        )
    }

    // 持有系统权限请求注册器，便于生命周期内接收结果。
    private val permissionRequest =
        // 同时注册近似与精确位置权限的结果回调。
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            // 通知 ViewModel 重新读取权限并按当前语言推进启动。
            viewModel.onPermissionResult(uiLanguage.locale.toLanguageTag())
        }

    // Activity 仅转交选择结果，恢复定位和保留旧天气的判断由 ViewModel 处理。
    private val locationSearchRequest =
        registerForActivityResult(LocationSearchContract()) { token ->
            // 把搜索返回 token 和当前语言转交状态机处理。
            viewModel.onLocationSearchResult(token, uiLanguage.locale.toLanguageTag())
        }

    private val viewModel: HomeViewModel by lazy {
        // 配置工厂注入依赖，旋转时复用同一个 ViewModel。
        ViewModelProvider(this, createViewModelFactory())[HomeViewModel::class.java]
    }

    /*
     * 页面选择自己的 ViewModel 作用域，并通过工厂注入业务依赖。
     */
    private fun createViewModelFactory(): ViewModelProvider.Factory = viewModelFactory {
        // 声明 HomeViewModel 的创建逻辑。
        initializer {
            // 创建首页启动状态机。
            HomeViewModel(
                // 注入共享定位、天气和会话能力。
                dependencies.deviceLocation, dependencies.weather, dependencies.sessions,
                // 优先使用恢复 token，否则读取本次路由传参。
                restoredSessionToken ?: intent.getStringExtra(NavigationExtras.SESSION_TOKEN),
                // 恢复等待地点选择的状态，避免重复启动自动定位。
                restoredChangingLocation = restoredChangingLocation,
            )
        }
    }

    /*
     * 绑定当前视图的展示与交互回调，每次视图创建时重新执行。
     */
    override fun initBinding(binding: ActivityHomeBinding, savedInstanceState: Bundle?) {
        // 读取上次保存的随机会话标识。
        restoredSessionToken = savedInstanceState?.getString(SAVED_SESSION_TOKEN)
        // 读取是否仍等待搜索结果，首次启动默认不等待。
        restoredChangingLocation = savedInstanceState?.getBoolean(SAVED_CHANGING_LOCATION) ?: false
        // 创建渲染器并绑定地点切换意图。
        renderer = HomeRenderer(binding, viewModel::onChangeLocationRequested) {
            // 将空态操作交给 ViewModel 按失败原因决定行为。
            viewModel.onFallbackAction(uiLanguage.locale.toLanguageTag())
        }
    }

    /*
     * 在可见生命周期内收集状态；停止收集时不销毁已有 ViewModel 数据。
     */
    override suspend fun CoroutineScope.observeData() {
        // 独立收集页面状态，不阻塞一次性事件消费。
        launch {
            // 对每次状态快照刷新首页内容。
            viewModel.state.collect { state ->
                // 更新加载、空态与天气容器的展示。
                renderer.render(state)
                // 同步天气 Fragment 与当前有效会话。
                renderWeatherFragment(state)
            }
        }
        // 并行收集系统权限、设置及搜索跳转事件。
        launch { viewModel.effects.collect(::handleEffect) }
    }

    /*
     * 解除当前视图关联的监听、适配器或渲染器引用。
     */
    override fun clearBinding() {
        // 页面销毁时解除渲染器持有的控件回调。
        renderer.clear()
    }

    /*
     * 按会话复用或替换天气 Fragment，失效时仅移除天气页面。
     */
    private fun renderWeatherFragment(state: HomeUiState) {
        // 获取天气容器当前 Fragment，决定是否复用。
        val current = supportFragmentManager.findFragmentById(R.id.home_weather)
        // 按启动阶段处理天气页面挂载。
        when (state.phase) {
            // 天气就绪后允许展示对应会话。
            HomePhase.READY -> {
                // 把会话 token 编入标签，区分不同地点的页面。
                val tag = "weather:${state.sessionToken}"
                // 仅会话变化时替换 Fragment，避免重复初始化。
                if (current?.tag != tag) {
                    // 按契约获取天气组件的页面工厂。
                    val weather =
                        ServiceLocator.get(ServiceRoutes.WEATHER, IWeatherService::class.java)
                            // 由天气组件创建对应会话的 Fragment。
                            .createFragment(state.sessionToken)
                    // 开始替换天气容器的事务。
                    supportFragmentManager.beginTransaction()
                        // 使用会话标签挂载新天气页面。
                        .replace(R.id.home_weather, weather, tag)
                        // 在可交互生命周期同步提交，避免下一次渲染看到旧页。
                        .commitNow()
                }
            }
            // 进入空态时仅移除属于天气会话的 Fragment。
            HomePhase.FALLBACK -> current?.takeIf { it.tag?.startsWith("weather:") == true }?.let {
                // 移除失效会话的页面，停止隐藏页面的订阅和定时刷新。
                supportFragmentManager.beginTransaction().remove(it).commitNow()
            }
            // 权限和加载阶段不进行额外 Fragment 事务。
            else -> Unit
        }
    }

    /*
     * 执行 ViewModel 决定的系统权限或设置操作。
     */
    private fun handleEffect(effect: HomeUiEffect) {
        // 按一次性事件执行对应 Android 平台操作。
        when (effect) {
            // 打开可回传地点选择结果的搜索页。
            HomeUiEffect.OpenLocationSearch -> locationSearchRequest.launch(Unit)
            // 发起系统位置权限申请。
            HomeUiEffect.RequestLocationPermission -> permissionRequest.launch(
                // 一起请求近似与精确权限，由系统允许用户选择。
                arrayOf(
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.ACCESS_FINE_LOCATION
                )
            )
            // 权限空态操作打开本应用系统设置。
            HomeUiEffect.OpenLocationSettings -> startActivity(
                // 定位到当前包的设置页，便于用户调整位置授权。
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$packageName".toUri())
            )
        }
    }

    /*
     * 更新当前 Intent，并把路由传入的会话交给 ViewModel 校验。
     */
    override fun onNewIntent(intent: Intent) {
        // 先交给基类处理复用 Activity 收到的新 Intent。
        super.onNewIntent(intent)
        // 保存最新 Intent，保证后续读取参数一致。
        setIntent(intent)
        // 将新会话标识交给 ViewModel 验证并取消旧启动。
        viewModel.onSessionReceived(intent.getStringExtra(NavigationExtras.SESSION_TOKEN))
    }

    /*
     * 页面进入前台时转发语言和刷新事件，复用已有的 ViewModel。
     */
    override fun onStart() {
        super.onStart()
        // 告知状态机回到前台并提供当前界面语言。
        viewModel.onForeground(uiLanguage.locale.toLanguageTag())
    }

    /*
     * 页面离开前台时暂停对应的定位或定时任务。
     */
    override fun onStop() {
        // 按是否配置重建决定取消或保留自动定位任务。
        viewModel.onBackground(isChangingConfigurations)
        // 完成 Activity 离开可见状态的系统回调。
        super.onStop()
    }

    /*
     * 仅保存随机会话标识，不把地点或天气写入系统恢复快照。
     */
    override fun onSaveInstanceState(outState: Bundle) {
        // 只保存当前随机 token，不保存地点或天气对象。
        outState.putString(SAVED_SESSION_TOKEN, viewModel.state.value.sessionToken)
        // 保存选择进行中标记，重建后继续等待结果。
        outState.putBoolean(SAVED_CHANGING_LOCATION, viewModel.isChangingLocation)
        // 交由基类保存其余系统状态。
        super.onSaveInstanceState(outState)
    }

    /**
     * 集中维护首页恢复快照的私有键名。
     */
    private companion object {
        // 保存首页当前会话标识的键。
        const val SAVED_SESSION_TOKEN = "home_session_token"

        // 保存首页等待地点选择状态的键。
        const val SAVED_CHANGING_LOCATION = "home_changing_location"
    }
}
