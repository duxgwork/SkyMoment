package com.example.skymoment.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.skymoment.domain.CoordinateWeatherRepository
import com.example.skymoment.domain.DataResult
import com.example.skymoment.domain.DeviceLocationProvider
import com.example.skymoment.domain.LocationSessionRepository
import com.example.skymoment.domain.WeatherFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.milliseconds

/**
 * 启动依次经过权限、定位、当前天气；任一步失败展示可重试的空态，成功结果复用于天气页。
 * 让启动任务随 ViewModel 生命周期管理。
 */
class HomeViewModel(
    // 注入权限检查及单次设备定位能力。
    private val location: DeviceLocationProvider,
    // 注入当前天气查询接口，隔离供应商实现。
    private val weather: CoordinateWeatherRepository,
    // 注入搜索与天气共享的地点会话仓储。
    private val sessions: LocationSessionRepository,
    // 接收重建或路由带来的可选会话 token。
    restoredToken: String? = null,
    // 接收重建前是否仍在等待搜索结果。
    restoredChangingLocation: Boolean = false,
) : ViewModel() {
    // 创建首页唯一的可变状态源。
    private val mutableState = MutableStateFlow(
        // 只有恢复 token 在当前进程内仍有效时才复用天气状态。
        if (restoredToken != null && sessions.findLocation(restoredToken) != null) HomeUiState(
            // 已有有效会话时直接进入就绪阶段。
            HomePhase.READY,
            // 沿用经过仓储验证的恢复 token。
            restoredToken
        )
        // 无法恢复时从权限检查阶段重新启动。
        else HomeUiState(HomePhase.PERMISSION),
    )

    // 对页面只暴露只读状态，限制外部修改。
    val state = mutableState.asStateFlow()

    // 缓冲暂时没有消费者的一次性平台事件。
    private val effectChannel = Channel<HomeUiEffect>(Channel.BUFFERED)

    // 把事件通道暴露为可由页面消费的流。
    val effects = effectChannel.receiveAsFlow()

    // 等待选择结果的状态由 ViewModel 管理，页面重建时可恢复。
    var isChangingLocation = restoredChangingLocation
        private set

    // 防止一次权限阶段重复弹出系统申请。
    private var permissionRequested = false

    // 用递增代次识别已失效的定位或天气结果。
    private var generation = 0

    // 持有当前启动任务，便于暂停或被手动选择替换。
    private var job: Job? = null

    /*
     * 回到前台后重新检查权限，自动恢复设置授权后的定位流程，并跳过已开始或已就绪请求。
     */
    fun onForeground(language: String) {
        // 用户正在搜索时不启动自动定位。
        if (isChangingLocation) return
        // 每次回到前台重新读取实际权限状态。
        val granted = location.hasPermission()
        // 从设置获得授权后重置原权限失败空态。
        if (granted && state.value.emptyReason == HomeEmptyReason.PERMISSION) retryStartup()
        // 用户修复网络后回到首页时恢复自动启动；配额、限流和数据错误仍由显式重试处理。
        if (granted && state.value.weatherFailure in setOf(
                WeatherFailure.NETWORK,
                WeatherFailure.TIMEOUT
            )
        ) {
            // 将可恢复网络失败重新置于启动入口。
            retryStartup()
        }
        // 已授权时尝试推进权限阶段后的流程。
        if (granted) permissionResult(true, language)
        // 未授权且尚未申请时发送一次权限事件。
        else if (shouldRequestPermission()) effectChannel.trySend(HomeUiEffect.RequestLocationPermission)
    }

    /*
     * 重新读取权限状态并推进启动流程，避免仅依赖某一项权限回调。
     */
    fun onPermissionResult(language: String) {
        // 以系统当前权限为准，不单独依赖某一权限回调值。
        val granted = location.hasPermission()
        // 搜索期间不启动定位；拒绝权限仍记录空态，取消搜索后不重复申请。
        if (!isChangingLocation || !granted) permissionResult(granted, language)
    }

    /*
     * 进入独立搜索前取消自动启动，防止其迟到结果覆盖用户即将选择的会话。
     */
    fun onChangeLocationRequested() {
        // 重复点击切换时不重复打开搜索页。
        if (isChangingLocation) return
        // 先标记正在选择，阻止前台回调抢先自动定位。
        isChangingLocation = true
        // 取消进行中的启动请求并使其结果失效。
        pauseStartup()
        // 请求页面打开独立地点搜索。
        effectChannel.trySend(HomeUiEffect.OpenLocationSearch)
    }

    /*
     * 选择后更新当前首页；取消时保留已有天气，未完成的启动可恢复。
     */
    fun onLocationSearchResult(token: String?, language: String) {
        // 搜索返回后解除等待选择状态。
        isChangingLocation = false
        // 取消时恢复合适的启动流程，有 token 时优先选择该会话。
        if (token.isNullOrBlank()) onForeground(language) else selectSession(token)
    }

    /*
     * 忽略空路由参数；有效参数由会话仓储验证后替换当前状态。
     */
    fun onSessionReceived(token: String?) {
        // 仅非空白路由 token 才触发会话切换。
        if (!token.isNullOrBlank()) selectSession(token)
    }

    /*
     * 按空态原因选择打开系统设置或重新启动定位天气流程。
     */
    fun onFallbackAction(language: String) {
        // 只在空态且没有搜索进行时接受恢复操作。
        if (isChangingLocation || state.value.phase != HomePhase.FALLBACK) return
        // 权限问题需要用户调整系统设置。
        if (state.value.emptyReason == HomeEmptyReason.PERMISSION) {
            // 发送打开应用权限设置的一次性事件。
            effectChannel.trySend(HomeUiEffect.OpenLocationSettings)
        } else {
            // 重置失败状态，准备重新检查权限。
            retryStartup()
            // 沿用当前语言重新进入启动流程。
            onForeground(language)
        }
    }

    /*
     * 非配置重建离开前台时取消定位并使旧代次失效；旋转保留进行中的请求。
     */
    fun onBackground(changingConfigurations: Boolean) {
        // 真正退后台且仍在定位时停止前台定位请求。
        if (!changingConfigurations && state.value.phase == HomePhase.LOCATING) {
            // 使当前定位失效，保留下一次前台恢复机会。
            pauseStartup()
        }
    }

    /*
     * 仅重置进行中的自动流程，已经就绪的地点与天气保持不变。
     */
    private fun pauseStartup() {
        // 只暂停实际进行中的定位或天气请求。
        if (state.value.phase != HomePhase.LOCATING && state.value.phase != HomePhase.WEATHER) return
        // 递增代次，拦截不响应取消的旧请求结果。
        generation++
        // 取消当前启动协程及其下游工作。
        job?.cancel()
        // 允许恢复后的权限阶段重新判断是否需要申请。
        permissionRequested = false
        // 回到统一启动入口，等待后续前台事件。
        mutableState.value = HomeUiState(HomePhase.PERMISSION)
    }

    /*
     * 只在权限阶段申请一次，防止生命周期恢复重复弹出权限窗口。
     */
    private fun shouldRequestPermission(): Boolean {
        // 非权限阶段或已发申请时不重复请求。
        if (state.value.phase != HomePhase.PERMISSION || permissionRequested) return false
        // 在发送事件前记录已申请，防止重入。
        permissionRequested = true
        // 允许调用方发送本次唯一的权限申请。
        return true
    }

    /*
     * 按授权结果进入空态或启动有超时限制的定位与天气请求。
     */
    private fun permissionResult(granted: Boolean, language: String) {
        // 只接受当前权限阶段的结果，避免打断已就绪状态。
        if (state.value.phase != HomePhase.PERMISSION) return
        // 未获位置授权时进入权限回退。
        if (!granted) {
            // 发布需要权限引导的首页状态。
            mutableState.value =
                    // 标记权限失败，供页面显示前往设置操作。
                HomeUiState(HomePhase.FALLBACK, emptyReason = HomeEmptyReason.PERMISSION)
            // 拒权后不继续定位或请求天气。
            return
        }
        // 为本轮定位和天气请求分配新代次。
        val request = ++generation
        // 先发布正在定位状态，更新启动步骤。
        mutableState.value = HomeUiState(HomePhase.LOCATING)
        // 在 ViewModel 作用域执行可取消的启动链路。
        job = viewModelScope.launch {
            try {
                // 最多等待十五秒获取有效设备坐标。
                val coordinates = withTimeoutOrNull(15_000.milliseconds) { location.locate() }
                // 本轮已被替换时立即丢弃定位结果。
                if (request != generation) return@launch
                // 无有效坐标或定位超时则回退。
                if (coordinates == null) {
                    // 发布可重新定位的空态。
                    mutableState.value =
                            // 保留定位失败原因，避免误报为天气接口故障。
                        HomeUiState(HomePhase.FALLBACK, emptyReason = HomeEmptyReason.LOCATION)
                    // 定位未成功时不请求天气。
                    return@launch
                }
                // 收到坐标后切换到获取天气的加载阶段。
                mutableState.value = HomeUiState(HomePhase.WEATHER)
                // 使用坐标和当前语言取首屏天气，最多等待三十秒。
                val result =
                    withTimeoutOrNull(30_000.milliseconds) {
                        weather.getWeatherAt(
                            coordinates,
                            language
                        )
                    }
                // 再次校验代次，避免旧天气覆盖手动选择。
                if (request != generation) return@launch
                // 只有成功天气结果才创建就绪会话。
                mutableState.value = if (result is DataResult.Success) {
                    // 构造绑定新会话的首页状态。
                    HomeUiState(
                        // 标记可以挂载天气页面。
                        HomePhase.READY,
                        // 将地点和首屏天气一起存入会话，避免天气页重复请求。
                        sessions.createSession(result.data.location, result.data.current)
                    )
                    // 失败、空结果或超时进入天气回退。
                } else HomeUiState(
                    // 标记启动链路已结束并可由用户恢复。
                    HomePhase.FALLBACK,
                    // 记录失败发生在天气获取阶段。
                    emptyReason = HomeEmptyReason.WEATHER,
                    // 外层等待超时明确保留超时原因。
                    weatherFailure = if (result == null) WeatherFailure.TIMEOUT
                    // 其他失败保留领域错误，合法空结果不伪造错误类别。
                    else (result as? DataResult.Failure)?.reason,
                )
            } catch (cancelled: CancellationException) {
                // 把取消交给协程体系完成下游清理。
                throw cancelled
            } catch (_: Exception) {
                // 只允许仍有效的请求发布异常空态。
                if (request == generation) mutableState.value = HomeUiState(
                    // 异常结束后展示可恢复空态。
                    HomePhase.FALLBACK,
                    // 按出错阶段区分天气故障和定位故障。
                    emptyReason = if (state.value.phase == HomePhase.WEATHER) HomeEmptyReason.WEATHER else HomeEmptyReason.LOCATION
                )
            }
        }
    }

    /*
     * 用户主动选择优先于所有尚未返回的自动定位／天气请求。
     */
    private fun selectSession(token: String) {
        // 切换会话时使所有旧自动请求结果失效。
        generation++
        // 取消正在执行的自动启动任务。
        job?.cancel()
        // 根据 token 是否仍有效生成新的首页状态。
        mutableState.value =
                // 有效会话立即展示对应天气。
            if (sessions.findLocation(token) != null) HomeUiState(HomePhase.READY, token)
            // 无效会话进入位置回退，避免显示已失效地点。
            else HomeUiState(HomePhase.FALLBACK, emptyReason = HomeEmptyReason.LOCATION)
    }

    /*
     * 将失败状态恢复到权限阶段，已就绪天气不会被重试清空。
     */
    private fun retryStartup() {
        // 只重置失败空态，不清空正常显示的天气。
        if (state.value.phase != HomePhase.FALLBACK) return
        // 重试时重新允许必要的权限检查和申请。
        permissionRequested = false
        // 统一从权限阶段开始下一轮定位天气流程。
        mutableState.value = HomeUiState(HomePhase.PERMISSION)
    }

}
