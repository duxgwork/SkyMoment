package com.example.skymoment.home

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
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
import com.example.skymoment.core.service.IApplicationService
import com.example.skymoment.core.service.IWeatherService
import com.example.skymoment.framework.BaseActivity
import com.example.skymoment.home.databinding.ActivityHomeBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 首页展示天气和地点切换入口，接收生命周期并执行 ViewModel 发出的平台操作。
 **/
@Route(path = Routes.HOME)
class HomeActivity : BaseActivity<ActivityHomeBinding>(ActivityHomeBinding::inflate) {
    private val viewModel: HomeViewModel by lazy(LazyThreadSafetyMode.NONE) {
        ViewModelProvider(this, createViewModelFactory())[HomeViewModel::class.java]
    }
    private lateinit var renderer: HomeRenderer
    private var restoredSessionToken: String? = null
    private var restoredChangingLocation = false
    override val observationState = Lifecycle.State.RESUMED
    private val dependencies by lazy {
        ServiceLocator.get(
            ServiceRoutes.APPLICATION,
            IApplicationService::class.java
        )
    }
    private val permissionRequest =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            viewModel.onPermissionResult(uiLanguage.locale.toLanguageTag())
        }
    // Activity 仅转交选择结果，恢复定位和保留旧天气的判断由 ViewModel 处理。
    private val locationSearchRequest = registerForActivityResult(LocationSearchContract()) { token ->
        viewModel.onLocationSearchResult(token, uiLanguage.locale.toLanguageTag())
    }

    // 页面选择自己的 ViewModel 作用域，并通过工厂注入业务依赖。
    private fun createViewModelFactory(): ViewModelProvider.Factory = viewModelFactory {
        initializer {
            HomeViewModel(
                dependencies.deviceLocation, dependencies.weather, dependencies.sessions,
                restoredSessionToken ?: intent.getStringExtra(NavigationExtras.SESSION_TOKEN),
                restoredChangingLocation = restoredChangingLocation,
            )
        }
    }

    // 绑定当前视图的展示与交互回调，每次视图创建时重新执行。
    override fun initBinding(binding: ActivityHomeBinding, savedInstanceState: Bundle?) {
        restoredSessionToken = savedInstanceState?.getString(SAVED_SESSION_TOKEN)
        restoredChangingLocation = savedInstanceState?.getBoolean(SAVED_CHANGING_LOCATION) ?: false
        renderer = HomeRenderer(binding, viewModel::onChangeLocationRequested) {
            viewModel.onFallbackAction(uiLanguage.locale.toLanguageTag())
        }
    }

    // 在可见生命周期内收集状态；停止收集时不销毁已有 ViewModel 数据。
    override suspend fun CoroutineScope.observeData() {
        launch {
            viewModel.state.collect { state ->
                renderer.render(state)
                renderWeatherFragment(state)
            }
        }
        launch { viewModel.effects.collect(::handleEffect) }
    }

    // 解除当前视图关联的监听、适配器或渲染器引用。
    override fun clearBinding() {
        renderer.clear()
    }

    // 按会话复用或替换天气 Fragment，失效时仅移除天气页面。
    private fun renderWeatherFragment(state: HomeUiState) {
        val current = supportFragmentManager.findFragmentById(R.id.home_weather)
        when (state.phase) {
            HomePhase.READY -> {
                val tag = "weather:${state.sessionToken}"
                if (current?.tag != tag) {
                    val weather = ServiceLocator.get(ServiceRoutes.WEATHER, IWeatherService::class.java)
                        .createFragment(state.sessionToken)
                    supportFragmentManager.beginTransaction()
                        .replace(R.id.home_weather, weather, tag)
                        .commitNow()
                }
            }
            HomePhase.FALLBACK -> current?.takeIf { it.tag?.startsWith("weather:") == true }?.let {
                // 移除失效会话的页面，停止隐藏页面的订阅和定时刷新。
                supportFragmentManager.beginTransaction().remove(it).commitNow()
            }
            else -> Unit
        }
    }

    // 执行 ViewModel 决定的系统权限或设置操作。
    private fun handleEffect(effect: HomeUiEffect) {
        when (effect) {
            HomeUiEffect.OpenLocationSearch -> locationSearchRequest.launch(Unit)
            HomeUiEffect.RequestLocationPermission -> permissionRequest.launch(
                arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
            )
            HomeUiEffect.OpenLocationSettings -> startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
            )
        }
    }

    // 更新当前 Intent，并把路由传入的会话交给 ViewModel 校验。
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        viewModel.onSessionReceived(intent.getStringExtra(NavigationExtras.SESSION_TOKEN))
    }

    // 页面进入前台时转发语言和刷新事件，复用已有的 ViewModel。
    override fun onStart() {
        super.onStart()
        viewModel.onForeground(uiLanguage.locale.toLanguageTag())
    }

    // 页面离开前台时暂停对应的定位或定时任务。
    override fun onStop() {
        viewModel.onBackground(isChangingConfigurations)
        super.onStop()
    }

    // 仅保存随机会话标识，不把地点或天气写入系统恢复快照。
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(SAVED_SESSION_TOKEN, viewModel.state.value.sessionToken)
        outState.putBoolean(SAVED_CHANGING_LOCATION, viewModel.isChangingLocation)
        super.onSaveInstanceState(outState)
    }

    private companion object {
        const val SAVED_SESSION_TOKEN = "home_session_token"
        const val SAVED_CHANGING_LOCATION = "home_changing_location"
    }
}
