package com.example.skymoment.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.skymoment.domain.DataResult
import com.example.skymoment.domain.DeviceLocationProvider
import com.example.skymoment.domain.LocationSessionRepository
import com.example.skymoment.domain.WeatherFailure
import com.example.skymoment.domain.WeatherRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** 启动依次经过权限、定位、当前天气；任一步失败展示可重试的空态，成功结果复用于天气页。 */
class HomeViewModel(
    private val location: DeviceLocationProvider,
    private val weather: WeatherRepository,
    private val sessions: LocationSessionRepository,
    restoredToken: String? = null,
    restoredChangingLocation: Boolean = false,
) : ViewModel() {
    private val mutableState = MutableStateFlow(
        if (restoredToken != null && sessions.resolve(restoredToken) != null) HomeUiState(
            HomePhase.READY,
            restoredToken
        )
        else HomeUiState(HomePhase.PERMISSION),
    )
    val state = mutableState.asStateFlow()
    private val effectChannel = Channel<HomeUiEffect>(Channel.BUFFERED)
    val effects = effectChannel.receiveAsFlow()
    // 等待选择结果的状态由 ViewModel 管理，页面重建时可恢复。
    var isChangingLocation = restoredChangingLocation
        private set
    private var permissionRequested = false
    private var generation = 0
    private var job: Job? = null

    // 回到前台后重新检查权限，自动恢复设置授权后的定位流程，并跳过已开始或已就绪请求。
    fun onForeground(language: String) {
        if (isChangingLocation) return
        val granted = location.hasPermission()
        if (granted && state.value.emptyReason == HomeEmptyReason.PERMISSION) retryStartup()
        // 用户修复网络后回到首页时恢复自动启动；配额、限流和数据错误仍由显式重试处理。
        if (granted && state.value.weatherFailure in setOf(WeatherFailure.NETWORK, WeatherFailure.TIMEOUT)) {
            retryStartup()
        }
        if (granted) permissionResult(true, language)
        else if (shouldRequestPermission()) effectChannel.trySend(HomeUiEffect.RequestLocationPermission)
    }

    // 重新读取权限状态并推进启动流程，避免仅依赖某一项权限回调。
    fun onPermissionResult(language: String) {
        val granted = location.hasPermission()
        // 搜索期间不启动定位；拒绝权限仍记录空态，取消搜索后不重复申请。
        if (!isChangingLocation || !granted) permissionResult(granted, language)
    }

    // 进入独立搜索前取消自动启动，防止其迟到结果覆盖用户即将选择的会话。
    fun onChangeLocationRequested() {
        if (isChangingLocation) return
        isChangingLocation = true
        pauseStartup()
        effectChannel.trySend(HomeUiEffect.OpenLocationSearch)
    }

    // 选择后更新当前首页；取消时保留已有天气，未完成的启动可恢复。
    fun onLocationSearchResult(token: String?, language: String) {
        isChangingLocation = false
        if (token.isNullOrBlank()) onForeground(language) else selectSession(token)
    }

    // 忽略空路由参数；有效参数由会话仓储验证后替换当前状态。
    fun onSessionReceived(token: String?) {
        if (!token.isNullOrBlank()) selectSession(token)
    }

    // 按空态原因选择打开系统设置或重新启动定位天气流程。
    fun onFallbackAction(language: String) {
        if (isChangingLocation || state.value.phase != HomePhase.FALLBACK) return
        if (state.value.emptyReason == HomeEmptyReason.PERMISSION) {
            effectChannel.trySend(HomeUiEffect.OpenLocationSettings)
        } else {
            retryStartup()
            onForeground(language)
        }
    }

    // 非配置重建离开前台时取消定位并使旧代次失效；旋转保留进行中的请求。
    fun onBackground(changingConfigurations: Boolean) {
        if (!changingConfigurations && state.value.phase == HomePhase.LOCATING) {
            pauseStartup()
        }
    }

    // 仅重置进行中的自动流程，已经就绪的地点与天气保持不变。
    private fun pauseStartup() {
        if (state.value.phase != HomePhase.LOCATING && state.value.phase != HomePhase.WEATHER) return
        generation++
        job?.cancel()
        permissionRequested = false
        mutableState.value = HomeUiState(HomePhase.PERMISSION)
    }

    // 只在权限阶段申请一次，防止生命周期恢复重复弹出权限窗口。
    private fun shouldRequestPermission(): Boolean {
        if (state.value.phase != HomePhase.PERMISSION || permissionRequested) return false
        permissionRequested = true
        return true
    }

    // 按授权结果进入空态或启动有超时限制的定位与天气请求。
    private fun permissionResult(granted: Boolean, language: String) {
        if (state.value.phase != HomePhase.PERMISSION) return
        if (!granted) {
            mutableState.value =
                HomeUiState(HomePhase.FALLBACK, emptyReason = HomeEmptyReason.PERMISSION)
            return
        }
        val request = ++generation
        mutableState.value = HomeUiState(HomePhase.LOCATING)
        job = viewModelScope.launch {
            try {
                val coordinates = withTimeoutOrNull(15_000) { location.locate() }
                if (request != generation) return@launch
                if (coordinates == null) {
                    mutableState.value =
                        HomeUiState(HomePhase.FALLBACK, emptyReason = HomeEmptyReason.LOCATION)
                    return@launch
                }
                mutableState.value = HomeUiState(HomePhase.WEATHER)
                val result = withTimeoutOrNull(30_000) { weather.currentAt(coordinates, language) }
                if (request != generation) return@launch
                mutableState.value = if (result is DataResult.Success) {
                    HomeUiState(
                        HomePhase.READY,
                        sessions.select(result.data.location, result.data.current)
                    )
                } else HomeUiState(
                    HomePhase.FALLBACK,
                    emptyReason = HomeEmptyReason.WEATHER,
                    weatherFailure = if (result == null) WeatherFailure.TIMEOUT
                        else (result as? DataResult.Failure)?.reason,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (request == generation) mutableState.value = HomeUiState(
                    HomePhase.FALLBACK,
                    emptyReason = if (state.value.phase == HomePhase.WEATHER) HomeEmptyReason.WEATHER else HomeEmptyReason.LOCATION
                )
            }
        }
    }

    /** 用户主动选择优先于所有尚未返回的自动定位／天气请求。 */
    private fun selectSession(token: String) {
        generation++
        job?.cancel()
        mutableState.value =
            if (sessions.resolve(token) != null) HomeUiState(HomePhase.READY, token)
            else HomeUiState(HomePhase.FALLBACK, emptyReason = HomeEmptyReason.LOCATION)
    }

    // 将失败状态恢复到权限阶段，已就绪天气不会被重试清空。
    private fun retryStartup() {
        if (state.value.phase != HomePhase.FALLBACK) return
        permissionRequested = false
        mutableState.value = HomeUiState(HomePhase.PERMISSION)
    }

}
