package com.example.skymoment.weather

import com.example.skymoment.domain.CurrentWeather
import com.example.skymoment.domain.DEVICE_LOCATION_PROVIDER
import com.example.skymoment.domain.DailyForecast
import com.example.skymoment.domain.DataResult
import com.example.skymoment.domain.Forecast
import com.example.skymoment.domain.HourlyForecast
import com.example.skymoment.domain.HourlyForecastEntry
import com.example.skymoment.domain.Location
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 单个天气区域的数据、加载、错误和空标记，区域之间互不覆盖。
 */
data class WeatherRegionUiState<T>(
    // 保留当前区域已获取的数据，刷新时可继续展示。
    val data: T? = null,
    // 独立记录当前区域是否正在加载。
    val loading: Boolean = false,
    // 保留当前区域的具体失败及限流时间。
    val error: DataResult.Failure? = null,
    // 区分合法空响应与尚未请求或请求失败。
    val empty: Boolean = false,
)

/**
 * 每次 ViewModel 更新快照时生成展示数据，Renderer 不再推导业务状态。
 */
data class WeatherUiState(
    // 保存当前有效会话的地点，失效时为空。
    val location: Location?,
    // 单独维护当前天气的数据与请求状态。
    val current: WeatherRegionUiState<CurrentWeather> = WeatherRegionUiState(),
    // 单独维护七日预报的数据与请求状态。
    val forecast: WeatherRegionUiState<Forecast> = WeatherRegionUiState(),
    // 单独维护小时预报的数据与请求状态。
    val hourly: WeatherRegionUiState<HourlyForecast> = WeatherRegionUiState(),
    // 保存地点当前整点，用于标记当前小时。
    val currentHour: Instant? = null,
    // 保存地点当地今天，用于日历窗口及标签。
    val today: LocalDate? = null,
    // 记录本次页面查询和展示对应的语言。
    val language: String = "",
    // 标记搜索地点名称更新是否失败。
    val nameError: Boolean = false,
    // 保存共享冷却剩余秒数，控制页面重试。
    val cooldownSeconds: Long = 0,
) {
    // 当前天气对象或任一有效日预报均可支撑内容展示。
    private val hasData: Boolean = current.data != null || forecast.data?.days?.any {
        // 日预报只要高温、低温或天气代码有一项就可显示。
        it.maximumCelsius != null || it.minimumCelsius != null || it.weatherCode != null
        // 若没有日预报数据，继续检查小时区域。
    } == true || hourly.data?.hours?.any {
        // 小时温度或天气代码任一存在即可展示。
        it.temperatureCelsius != null || it.weatherCode != null
        // 只有小时列表实际存在有效项才计入可展示数据。
    } == true

    // 地点失效时显示全空态，其余情况继续检查区域状态。
    val showEmpty: Boolean = location == null || (
            // 三个区域都无内容且均不在请求时才可能展示全空态。
            !hasData && !current.loading && !forecast.loading && !hourly.loading &&
                    // 同时要求当前天气已明确为空或失败，避免初始状态误判。
                    (current.empty || current.error != null)
            )

    // 限流倒计时归零后才开放恢复操作。
    val retryEnabled: Boolean = cooldownSeconds == 0L

    // 准备判断设备地点是否尚无实际名称。
    val showCurrentLocationName: Boolean = location?.let {
        // 只有设备来源且名称为空白时才显示当前位置标签。
        it.provider == DEVICE_LOCATION_PROVIDER && it.name.isBlank()
        // 没有有效地点时不构造当前位置名称状态。
    } == true

    // 从日预报中查找当地今天的高低温。
    val todayForecast: DailyForecast? = forecast.data?.days?.firstOrNull { it.date == today }

    // 把领域日预报转换为列表行状态。
    val dailyItems: List<DailyForecastItemUiState> = forecast.data?.days.orEmpty().map {
        // 逐日附加是否为今天的展示标记。
        DailyForecastItemUiState(it, it.date == today)
    }

    // 小时列表需要有效地点来确定展示时区。
    val hourlyItems: List<HourlyForecastItemUiState> = location?.let { place ->
        // 把每个小时记录转换为卡片展示状态。
        hourly.data?.hours.orEmpty().map {
            // 附加地点时区、当前小时标记和当地日期。
            HourlyForecastItemUiState(it, place.timeZone, it.time == currentHour, today)
        }
        // 没有地点或小时数据时向列表提供空集合。
    }.orEmpty()
}

/**
 * today 纳入 Diff，午夜刷新同一日期的标签也会重新绑定。
 */
data class DailyForecastItemUiState(val day: DailyForecast, val today: Boolean)

/**
 * 包含小时数据、地点时区和当前／今日标记，供列表直接展示。
 */
data class HourlyForecastItemUiState(
    // 保存该卡片对应的绝对小时及指标。
    val hour: HourlyForecastEntry,
    // 保存小时显示所用地点时区。
    val zone: ZoneId,
    // 标识该卡片是否对应当前窗口起点。
    val current: Boolean,
    // 保存地点当地今天，便于区分跨日小时。
    val today: LocalDate?,
)
