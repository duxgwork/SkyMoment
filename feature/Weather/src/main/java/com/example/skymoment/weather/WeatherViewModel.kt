package com.example.skymoment.weather

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.skymoment.domain.CurrentWeather
import com.example.skymoment.domain.DEVICE_LOCATION_PROVIDER
import com.example.skymoment.domain.DataResult
import com.example.skymoment.domain.Forecast
import com.example.skymoment.domain.ForecastWindow
import com.example.skymoment.domain.HourlyForecast
import com.example.skymoment.domain.HourlyForecastWindow
import com.example.skymoment.domain.Location
import com.example.skymoment.domain.LocationRepository
import com.example.skymoment.domain.LocationSessionRepository
import com.example.skymoment.domain.WeatherRepository
import com.example.skymoment.domain.map
import java.time.Clock
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 三个天气区域独立加载；时效与日历窗口按地点时区计算，名称更新不阻塞数值展示。 */
class WeatherViewModel(
    private val weather: WeatherRepository,
    sessions: LocationSessionRepository,
    token: String,
    private val locations: LocationRepository? = null,
    private val clock: Clock = Clock.systemUTC(),
) : ViewModel() {
    private val mutableState = MutableStateFlow(WeatherUiState(
        sessions.resolve(token), current = WeatherRegionUiState(data = sessions.initialWeather(token)),
    ))
    val state = mutableState.asStateFlow()
    private val window = ForecastWindow(clock)
    private val hourlyWindow = HourlyForecastWindow(clock)
    private val currentRequest = RegionRequest(
        read = { it.current },
        write = { copy(current = it) },
        fetch = weather::current,
    )
    private val forecastRequest = RegionRequest(
        read = { it.forecast },
        write = { copy(forecast = it) },
        fetch = { location, language ->
            weather.forecast(location, language).map { window.retain(it, location.timeZone) }
        },
    )
    private val hourlyRequest = RegionRequest(
        read = { it.hourly },
        write = { copy(hourly = it) },
        fetch = { location, language ->
            weather.hourly(location, language).map { hourlyWindow.retain(it, location.timeZone) }
        },
    )
    private var hourBoundaryJob: Job? = null
    private var nameJob: Job? = null
    private var midnightJob: Job? = null
    private var cooldownJob: Job? = null
    private var generation = 0
    private var foreground = false
    private var deviceNameRequested = false

    // 回到前台后根据已有状态决定恢复、刷新或跳过重复请求。
    fun onForeground(language: String) {
        foreground = true
        val location = state.value.location ?: return
        val changedLanguage = state.value.language.isNotEmpty() && state.value.language != language
        val changedDate = state.value.today != window.today(location.timeZone)
        val changedHour = state.value.currentHour != hourlyWindow.currentHour(location.timeZone)
        if (changedLanguage) {
            generation++
            currentRequest.cancel()
            forecastRequest.cancel()
            hourlyRequest.cancel()
            nameJob?.cancel()
        }
        val needsDeviceName = location.provider == DEVICE_LOCATION_PROVIDER && !deviceNameRequested
        if (needsDeviceName || changedLanguage || (state.value.nameError && nameJob?.isActive != true)) {
            deviceNameRequested = true
            refreshName(language)
        }
        mutableState.value = state.value.copy(language = language, today = window.today(location.timeZone))
        pruneDates()
        val currentTime = state.value.current.data?.fetchedAt
        val forecastTime = state.value.forecast.data?.fetchedAt
        val hourlyTime = state.value.hourly.data?.fetchedAt
        if (changedLanguage || isStale(currentTime, 10)) currentRequest.load()
        if (changedLanguage || changedDate || isStale(forecastTime, 60)) forecastRequest.load()
        if (changedLanguage || changedHour || isStale(hourlyTime, 60)) loadHourly()
        scheduleMidnight()
        scheduleHourBoundary()
        updateCooldown()
    }

    // 退出前台时取消当前页面不再需要的任务，保留可复用状态。
    fun onBackground() {
        foreground = false
        midnightJob?.cancel()
        hourBoundaryJob?.cancel()
        cooldownJob?.cancel()
    }

    // 校准日期窗口后刷新三个区域，必要时独立重试地点译名。
    fun refresh() {
        pruneDates()
        currentRequest.load()
        forecastRequest.load()
        loadHourly()
        if (state.value.nameError && nameJob?.isActive != true) refreshName(state.value.language)
    }
    // 只重试当前天气区域，沿用区域请求去重与冷却限制。
    fun retryCurrent() = currentRequest.load()
    // 只重试七日预报区域，不影响其他已展示的数据。
    fun retryForecast() = forecastRequest.load()
    // 重新校准小时窗口后，仅重试小时预报。
    fun retryHourly() = loadHourly()

    // 按注入的时钟计算数据时效，缺少获取时间时视为需要刷新。
    private fun isStale(fetchedAt: Instant?, minutes: Long): Boolean =
        fetchedAt == null || Duration.between(fetchedAt, clock.instant()).toMinutes() >= minutes

    // 按地点时区剔除过期预报，同时更新今日与当前小时标记。
    private fun pruneDates() {
        val location = state.value.location ?: return
        val today = window.today(location.timeZone)
        val forecast = state.value.forecast.data?.let { window.retain(it, location.timeZone) }
        val hourly = state.value.hourly.data?.let { hourlyWindow.retain(it, location.timeZone) }
        mutableState.value = state.value.copy(
            today = today,
            forecast = state.value.forecast.copy(data = forecast?.takeIf { it.days.isNotEmpty() }),
            currentHour = hourlyWindow.currentHour(location.timeZone),
            hourly = state.value.hourly.copy(data = hourly),
        )
    }

    // 先更新当前小时窗口，再进入共享的区域请求逻辑。
    private fun loadHourly() {
        pruneDates()
        hourlyRequest.load()
    }

    /**
     * 每个区域拥有自己的任务；重复点击合并，失败保留已有内容，只有明确空结果才清空。
     * read/write 始终操作最新快照，防止并发完成的请求覆盖其他区域刚更新的数据。
     */
    private inner class RegionRequest<T>(
        private val read: (WeatherUiState) -> WeatherRegionUiState<T>,
        private val write: WeatherUiState.(WeatherRegionUiState<T>) -> WeatherUiState,
        private val fetch: suspend (Location, String) -> DataResult<T>,
    ) {
        private var job: Job? = null

        // 合并重复请求，保留刷新前的数据，并按代次合并新的区域结果。
        fun load() {
            val location = state.value.location ?: return
            if (job?.isActive == true || coolingDown()) return
            val request = generation
            val language = state.value.language
            mutableState.value = state.value.write(read(state.value).copy(loading = true, error = null, empty = false))
            job = viewModelScope.launch {
                val result = fetch(location, language)
                // 取消是协作式的；代次校验也拦截未响应取消的旧请求。
                if (request != generation) return@launch
                val region = when (result) {
                    is DataResult.Success -> WeatherRegionUiState(data = result.data)
                    is DataResult.Failure -> read(state.value).copy(loading = false, error = result)
                    DataResult.Empty -> WeatherRegionUiState<T>(empty = true)
                }
                mutableState.value = state.value.write(region)
                updateCooldown()
            }
        }

        // 取消区域请求并清除加载标记，保留已有可用数据。
        fun cancel() {
            job?.cancel()
            job = null
            mutableState.value = state.value.write(read(state.value).copy(loading = false))
        }
    }

    // 独立更新地点名称；旧代次响应和设备译名失败不覆盖当前展示。
    private fun refreshName(language: String) {
        val source = locations ?: return
        val location = state.value.location ?: return
        val request = generation
        nameJob = viewModelScope.launch {
            when (val result = source.refresh(location, language)) {
                is DataResult.Success -> if (request == generation && result.data.id == location.id) {
                    mutableState.value = state.value.copy(location = result.data, nameError = false)
                }
                else -> if (request == generation && location.provider != DEVICE_LOCATION_PROVIDER) {
                    mutableState.value = state.value.copy(nameError = true)
                }
            }
        }
    }

    // 汇总三个天气区域的限流恢复时间。
    private fun retryTimes() = listOfNotNull(state.value.current.error?.retryAt,
        state.value.forecast.error?.retryAt, state.value.hourly.error?.retryAt)

    // 只要任一区域仍处于服务器指定冷却期，就阻止新的天气请求。
    private fun coolingDown(): Boolean = retryTimes()
        .any { it > clock.instant() }

    // 更新剩余冷却秒数，前台按秒刷新，后台不持续计时。
    private fun updateCooldown() {
        cooldownJob?.cancel()
        val until = retryTimes().maxOrNull()
        // 将剩余毫秒向上取整为秒，并将过期时间钳制为零。
        fun seconds() = until?.let { ((Duration.between(clock.instant(), it).toMillis() + 999) / 1000).coerceAtLeast(0) } ?: 0L
        mutableState.value = state.value.copy(cooldownSeconds = seconds())
        if (foreground && seconds() > 0) {
            cooldownJob = viewModelScope.launch {
                while (foreground && seconds() > 0) {
                    delay(1000)
                    mutableState.value = state.value.copy(cooldownSeconds = seconds())
                }
            }
        }
    }

    // 按地点日历的下一个午夜调度，跨日先裁剪旧预报再刷新。
    private fun scheduleMidnight() {
        midnightJob?.cancel()
        val zone = state.value.location?.timeZone ?: return
        midnightJob = viewModelScope.launch {
            while (foreground) {
                val next = window.today(zone).plusDays(1).atStartOfDay(zone).toInstant()
                delay(Duration.between(clock.instant(), next).toMillis().coerceAtLeast(1))
                pruneDates()
                forecastRequest.load()
            }
        }
    }

    // 按绝对时间调度下个整点，校准小时窗口并刷新小时预报。
    private fun scheduleHourBoundary() {
        hourBoundaryJob?.cancel()
        val zone = state.value.location?.timeZone ?: return
        hourBoundaryJob = viewModelScope.launch {
            while (foreground) {
                val next = hourlyWindow.currentHour(zone).plusSeconds(3600)
                delay(Duration.between(clock.instant(), next).toMillis().coerceAtLeast(1))
                pruneDates()
                loadHourly()
            }
        }
    }
}
