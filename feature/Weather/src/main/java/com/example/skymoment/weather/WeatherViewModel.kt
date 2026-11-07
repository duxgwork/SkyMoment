package com.example.skymoment.weather

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.skymoment.domain.DEVICE_LOCATION_PROVIDER
import com.example.skymoment.domain.DataResult
import com.example.skymoment.domain.ForecastWindow
import com.example.skymoment.domain.HourlyForecastWindow
import com.example.skymoment.domain.Location
import com.example.skymoment.domain.LocationNameRepository
import com.example.skymoment.domain.LocationSessionRepository
import com.example.skymoment.domain.SelectedLocationWeatherRepository
import com.example.skymoment.domain.map
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.Duration
import java.time.Instant
import kotlin.time.Duration.Companion.milliseconds

/**
 * 三个天气区域独立加载；时效与日历窗口按地点时区计算，名称更新不阻塞数值展示。
 * 由 ViewModel 生命周期管理区域请求和定时协程。
 */
class WeatherViewModel(
    // 注入当前、小时和七日天气查询能力。
    private val weather: SelectedLocationWeatherRepository,
    // 接收共享会话仓储，解析页面所属地点。
    sessions: LocationSessionRepository,
    // 接收跨组件传入的唯一会话标识。
    token: String,
    // 可选注入地点名称刷新能力，允许测试或简化场景省略。
    private val locations: LocationNameRepository? = null,
    // 注入统一时钟，便于时效、午夜及整点的确定性验证。
    private val clock: Clock = Clock.systemUTC(),
) : ViewModel() {
    // 创建天气页唯一可变状态源。
    private val mutableState = MutableStateFlow(
        WeatherUiState(
            // 从有效会话解析地点，并复用启动已取得的当前天气。
            sessions.findLocation(token),
            current = WeatherRegionUiState(data = sessions.findInitialWeather(token)),
        )
    )

    // 只向渲染层公开不可变状态流。
    val state = mutableState.asStateFlow()

    // 创建以地点当地日期裁剪七日预报的窗口。
    private val window = ForecastWindow(clock)

    // 创建以绝对小时维护二十四小时预报的窗口。
    private val hourlyWindow = HourlyForecastWindow(clock)

    // 为当前天气配置独立请求控制器。
    private val currentRequest = WeatherRegionRequest(
        // 从最新页面快照读取当前天气区域。
        read = { it.current },
        // 仅替换当前天气区域，保留其他并发区域结果。
        write = { copy(current = it) },
        // 把实际数据获取委托给当前天气仓储方法。
        fetch = weather::getCurrentWeather,
    )

    // 为日预报配置独立请求控制器。
    private val forecastRequest = WeatherRegionRequest(
        // 读取当前快照中的日预报区域。
        read = { it.forecast },
        // 只写回日预报区域状态。
        write = { copy(forecast = it) },
        // 接收查询地点及本次界面语言。
        fetch = { location, language ->
            // 在成功响应上再次校准本地七日窗口。
            weather.getDailyForecast(location, language)
                .map { window.retain(it, location.timeZone) }
        },
    )

    // 为小时预报配置独立请求控制器。
    private val hourlyRequest = WeatherRegionRequest(
        // 读取当前快照中的小时预报区域。
        read = { it.hourly },
        // 只写回小时区域状态，避免覆盖当前天气与日预报。
        write = { copy(hourly = it) },
        // 接收该次小时请求的地点与语言。
        fetch = { location, language ->
            // 对成功小时响应应用当前地点的二十四小时窗口。
            weather.getHourlyForecast(location, language)
                .map { hourlyWindow.retain(it, location.timeZone) }
        },
    )

    // 保存前台跨整点刷新任务，便于停止或重建。
    private var hourBoundaryJob: Job? = null

    // 保存独立名称刷新任务，避免名称阻塞天气数值。
    private var nameJob: Job? = null

    // 保存前台跨午夜的日预报刷新任务。
    private var midnightJob: Job? = null

    // 保存只在前台更新的限流倒计时任务。
    private var cooldownJob: Job? = null

    // 记录请求代次，防止语言切换后的旧结果覆盖新状态。
    private var generation = 0

    // 标记页面是否在前台，限制定时任务活动范围。
    private var foreground = false

    // 记录设备地点是否已经尝试名称解析。
    private var deviceNameRequested = false

    /*
     * 回到前台后根据已有状态决定恢复、刷新或跳过重复请求。
     */
    fun onForeground(language: String) {
        // 标记可见状态以启用前台定时调度。
        foreground = true
        // 没有有效会话地点时不发起任何天气请求。
        val location = state.value.location ?: return
        // 已初始化语言改变时才视为语言切换。
        val changedLanguage = state.value.language.isNotEmpty() && state.value.language != language
        // 比较上次记录日期与地点当前日期，识别跨日恢复。
        val changedDate = state.value.today != window.today(location.timeZone)
        // 比较上次整点与地点当前整点，识别跨小时恢复。
        val changedHour = state.value.currentHour != hourlyWindow.currentHour(location.timeZone)
        // 语言改变时终止旧语言相关任务。
        if (changedLanguage) {
            // 使所有旧代次响应失效，即使下游未及时响应取消。
            generation++
            // 取消旧的当前天气任务并清除其加载标记。
            currentRequest.cancel()
            // 取消旧的日预报任务并清除其加载标记。
            forecastRequest.cancel()
            // 取消旧的小时任务并清除其加载标记。
            hourlyRequest.cancel()
            // 取消旧语言的名称解析，避免名称错用语言。
            nameJob?.cancel()
        }
        // 仅首次进入设备地点时自动尝试名称补齐。
        val needsDeviceName = location.provider == DEVICE_LOCATION_PROVIDER && !deviceNameRequested
        // 首次设备名称、语言切换或上次名称失败时允许更新。
        if (needsDeviceName || changedLanguage || (state.value.nameError && nameJob?.isActive != true)) {
            // 在请求前记录已尝试设备名称，防止普通前台恢复重复查询。
            deviceNameRequested = true
            // 独立启动名称更新，不等待它完成再请求天气。
            refreshName(language)
        }
        // 把当前语言与当地日期同步到页面快照。
        mutableState.value =
            state.value.copy(language = language, today = window.today(location.timeZone))
        // 先裁剪过期日期和小时，再判断是否需要网络刷新。
        pruneDates()
        // 读取当前天气获取时间以判断十分钟时效。
        val currentTime = state.value.current.data?.fetchedAt
        // 读取日预报获取时间以判断一小时时效。
        val forecastTime = state.value.forecast.data?.fetchedAt
        // 读取小时预报获取时间以判断一小时时效。
        val hourlyTime = state.value.hourly.data?.fetchedAt
        // 语言改变或当前数据缺失、过期时加载当前天气。
        if (changedLanguage || isStale(currentTime, 10)) currentRequest.load()
        // 语言改变、跨日或日预报过期时重新获取日预报。
        if (changedLanguage || changedDate || isStale(forecastTime, 60)) forecastRequest.load()
        // 语言改变、跨整点或小时预报过期时重新获取小时数据。
        if (changedLanguage || changedHour || isStale(hourlyTime, 60)) loadHourly()
        // 安排下一次当地午夜刷新。
        scheduleMidnight()
        // 安排下一次绝对小时边界刷新。
        scheduleHourBoundary()
        // 根据已有区域错误恢复共享冷却倒计时。
        updateCooldown()
    }

    /*
     * 退出前台时取消当前页面不再需要的任务，保留可复用状态。
     */
    fun onBackground() {
        // 标记页面离开前台，终止持续定时刷新条件。
        foreground = false
        // 停止后台不需要的午夜计时。
        midnightJob?.cancel()
        // 停止后台不需要的整点计时。
        hourBoundaryJob?.cancel()
        // 停止后台倒计时更新，保留已有数据与错误。
        cooldownJob?.cancel()
    }

    /*
     * 校准日期窗口后刷新三个区域，必要时独立重试地点译名。
     */
    fun refresh() {
        // 全量重试前先更新地点的日期和小时窗口。
        pruneDates()
        // 重新加载当前天气区域，沿用去重和冷却判断。
        currentRequest.load()
        // 重新加载日预报区域，保持区域状态独立。
        forecastRequest.load()
        // 校准小时窗口并重新加载小时预报。
        loadHourly()
        // 名称此前失败且没有在途请求时一并独立重试。
        if (state.value.nameError && nameJob?.isActive != true) refreshName(state.value.language)
    }

    /*
     * 只重试当前天气区域，沿用区域请求去重与冷却限制。
     */
    fun retryCurrent() = currentRequest.load()

    /*
     * 只重试七日预报区域，不影响其他已展示的数据。
     */
    fun retryForecast() = forecastRequest.load()

    /*
     * 重新校准小时窗口后，仅重试小时预报。
     */
    fun retryHourly() = loadHourly()

    /*
     * 按注入的时钟计算数据时效，缺少获取时间时视为需要刷新。
     */
    private fun isStale(fetchedAt: Instant?, minutes: Long): Boolean =
        // 缺少获取时间或达到分钟阈值时视为数据过期。
        fetchedAt == null || Duration.between(fetchedAt, clock.instant()).toMinutes() >= minutes

    /*
     * 按地点时区剔除过期预报，同时更新今日与当前小时标记。
     */
    private fun pruneDates() {
        // 没有有效地点就无法计算当地时间窗口。
        val location = state.value.location ?: return
        // 根据地点时区读取当前日历日期。
        val today = window.today(location.timeZone)
        // 仅对已有日预报裁剪日期，不凭空生成天气。
        val forecast = state.value.forecast.data?.let { window.retain(it, location.timeZone) }
        // 仅对已有小时数据重新对齐时间窗口及空位。
        val hourly = state.value.hourly.data?.let { hourlyWindow.retain(it, location.timeZone) }
        // 基于当前快照更新时效相关派生状态。
        mutableState.value = state.value.copy(
            // 保存重新计算的当地今天。
            today = today,
            // 清除已无任何有效日期的旧日预报，保留其余区域标记。
            forecast = state.value.forecast.copy(data = forecast?.takeIf { it.days.isNotEmpty() }),
            // 保存地点当前整点作为小时卡片的定位基准。
            currentHour = hourlyWindow.currentHour(location.timeZone),
            // 写回校准后的小时列表，保留小时区域错误等状态。
            hourly = state.value.hourly.copy(data = hourly),
        )
    }

    /*
     * 先更新当前小时窗口，再进入共享的区域请求逻辑。
     */
    private fun loadHourly() {
        // 发起小时请求前先剔除过时卡片并补齐窗口。
        pruneDates()
        // 交给小时请求控制器执行网络查询。
        hourlyRequest.load()
    }

    /**
     * 每个区域拥有自己的任务；重复点击合并，失败保留已有内容，只有明确空结果才清空。
     * read/write 始终操作最新快照，防止并发完成的请求覆盖其他区域刚更新的数据。
     */
    private inner class WeatherRegionRequest<T>(
        // 注入读取目标区域的方法，使控制器可复用。
        private val read: (WeatherUiState) -> WeatherRegionUiState<T>,
        // 注入只替换目标区域的方法，避免并发区域互相覆盖。
        private val write: WeatherUiState.(WeatherRegionUiState<T>) -> WeatherUiState,
        // 注入获取该区域数据的挂起业务方法。
        private val fetch: suspend (Location, String) -> DataResult<T>,
    ) {
        // 每个区域保存自己的在途任务以合并重复点击。
        private var job: Job? = null

        /*
         * 合并重复请求，保留刷新前的数据，并按代次合并新的区域结果。
         */
        fun load() {
            // 没有有效地点时拒绝该区域请求。
            val location = state.value.location ?: return
            // 同区域已有任务或任一区域仍在冷却时不重复发送。
            if (job?.isActive == true || coolingDown()) return
            // 捕获当前代次，用于响应到达后校验。
            val request = generation
            // 捕获当前语言，保持一次请求上下文一致。
            val language = state.value.language
            // 保留旧数据但清理旧错误与空态，并标记加载中。
            mutableState.value = state.value.write(
                read(state.value).copy(
                    loading = true,
                    error = null,
                    empty = false
                )
            )
            // 在页面状态机作用域内发起独立区域查询。
            job = viewModelScope.launch {
                // 使用本次捕获的地点和语言请求实际数据。
                val result = fetch(location, language)
                // 取消是协作式的；代次校验也拦截未响应取消的旧请求。
                if (request != generation) return@launch
                // 根据领域结果生成目标区域的新快照。
                val region = when (result) {
                    // 成功响应替换该区域数据并清除旧的加载及错误状态。
                    is DataResult.Success -> WeatherRegionUiState(data = result.data)
                    // 失败保留最新已有数据，只结束加载并记录原因。
                    is DataResult.Failure -> read(state.value).copy(loading = false, error = result)
                    // 合法空响应清空该区域数据并标记为空。
                    DataResult.Empty -> WeatherRegionUiState<T>(empty = true)
                }
                // 基于最新全页状态只写回本次请求所属区域。
                mutableState.value = state.value.write(region)
                // 请求完成后重新计算所有区域共享的冷却状态。
                updateCooldown()
            }
        }

        /*
         * 取消区域请求并清除加载标记，保留已有可用数据。
         */
        fun cancel() {
            // 取消当前区域尚未完成的工作。
            job?.cancel()
            // 清空任务引用，允许之后的新语言或重试请求。
            job = null
            // 只清除加载标记，保留已有可用内容及其他区域。
            mutableState.value = state.value.write(read(state.value).copy(loading = false))
        }
    }

    /*
     * 独立更新地点名称；旧代次响应和设备译名失败不覆盖当前展示。
     */
    private fun refreshName(language: String) {
        // 未注入地点仓储时无需更新名称。
        val source = locations ?: return
        // 当前无有效地点时不发起名称查询。
        val location = state.value.location ?: return
        // 捕获名称请求的代次以识别迟到结果。
        val request = generation
        // 名称请求使用独立任务，不阻塞天气区域。
        nameJob = viewModelScope.launch {
            // 按来源调用供应商 ID 查询或系统逆地理编码。
            when (val result = source.refreshLocationName(location, language)) {
                // 只接受当前代次且地点 ID 一致的成功名称结果。
                is DataResult.Success -> if (request == generation && result.data.id == location.id) {
                    // 更新地点信息并清除此前名称错误。
                    mutableState.value = state.value.copy(location = result.data, nameError = false)
                }
                // 仅对非设备地点的当前代次失败标记名称异常。
                else -> if (request == generation && location.provider != DEVICE_LOCATION_PROVIDER) {
                    // 保留现有地点和天气，只展示名称更新失败提示。
                    mutableState.value = state.value.copy(nameError = true)
                }
            }
        }
    }

    /*
     * 汇总三个天气区域的限流恢复时间。
     */
    private fun retryTimes() = listOfNotNull(
        state.value.current.error?.retryAt,
        // 一并收集日预报与小时预报的限流恢复时刻。
        state.value.forecast.error?.retryAt, state.value.hourly.error?.retryAt
    )

    /*
     * 只要任一区域仍处于服务器指定冷却期，就阻止新的天气请求。
     */
    private fun coolingDown(): Boolean = retryTimes()
        // 只要存在未来截止时间就认为仍在共享冷却。
        .any { it > clock.instant() }

    /*
     * 更新剩余冷却秒数，前台按秒刷新，后台不持续计时。
     */
    private fun updateCooldown() {
        // 先取消旧倒计时，避免重复任务同时更新页面。
        cooldownJob?.cancel()
        // 选择所有区域中最晚的恢复时刻作为共享限制。
        val until = retryTimes().maxOrNull()

        /*
         * 将剩余毫秒向上取整为秒，并将过期时间钳制为零。
         */
        fun seconds() = until?.let {
            ((Duration.between(clock.instant(), it).toMillis() + 999) / 1000).coerceAtLeast(0)
        } ?: 0L
        // 立即更新当前剩余秒数，使重试按钮状态同步。
        mutableState.value = state.value.copy(cooldownSeconds = seconds())
        // 仅页面在前台且仍有等待时间时启动倒计时。
        if (foreground && seconds() > 0) {
            // 用可取消协程按秒更新冷却显示。
            cooldownJob = viewModelScope.launch {
                // 前台且未到期期间继续计时。
                while (foreground && seconds() > 0) {
                    // 非阻塞等待一秒，避免占用主线程。
                    delay(1000)
                    // 每次重新按时钟计算，避免计时累计漂移。
                    mutableState.value = state.value.copy(cooldownSeconds = seconds())
                }
            }
        }
    }

    /*
     * 按地点日历的下一个午夜调度，跨日先裁剪旧预报再刷新。
     */
    private fun scheduleMidnight() {
        // 重新安排午夜任务前取消旧任务。
        midnightJob?.cancel()
        // 没有地点时区时不启动日历定时器。
        val zone = state.value.location?.timeZone ?: return
        // 在 ViewModel 作用域内安排日预报跨日刷新。
        midnightJob = viewModelScope.launch {
            // 仅前台持续安排后续午夜边界。
            while (foreground) {
                // 按地点时区计算下一天零点的绝对时刻。
                val next = window.today(zone).plusDays(1).atStartOfDay(zone).toInstant()
                // 等待实际午夜间隔，最少一毫秒避免忙循环。
                delay(Duration.between(clock.instant(), next).toMillis().coerceAtLeast(1).milliseconds)
                // 跨日后先清理过期日预报和小时窗口。
                pruneDates()
                // 独立刷新七日预报，保留其他区域已有内容。
                forecastRequest.load()
            }
        }
    }

    /*
     * 按绝对时间调度下个整点，校准小时窗口并刷新小时预报。
     */
    private fun scheduleHourBoundary() {
        // 重新安排小时边界前取消旧任务。
        hourBoundaryJob?.cancel()
        // 需要有效地点时区来确定当前整点。
        val zone = state.value.location?.timeZone ?: return
        // 启动前台小时窗口刷新任务。
        hourBoundaryJob = viewModelScope.launch {
            // 页面保持前台时持续处理下一个小时边界。
            while (foreground) {
                // 从当前当地整点对应的绝对时刻前进一小时。
                val next = hourlyWindow.currentHour(zone).plusSeconds(3600)
                // 等待下一小时到来，兼容夏令时和时钟变化。
                delay(
                    Duration.between(clock.instant(), next).toMillis().coerceAtLeast(1).milliseconds
                )
                // 边界到达后先对已有数据重新校准。
                pruneDates()
                // 重新加载小时区域以补充新的窗口尾部。
                loadHourly()
            }
        }
    }
}
