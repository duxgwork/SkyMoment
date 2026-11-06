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

/** 单个天气区域的数据、加载、错误和空标记，区域之间互不覆盖。 */
data class WeatherRegionUiState<T>(
    val data: T? = null,
    val loading: Boolean = false,
    val error: DataResult.Failure? = null,
    val empty: Boolean = false,
)

/** 每次 ViewModel 更新快照时生成展示数据，Renderer 不再推导业务状态。 */
data class WeatherUiState(
    val location: Location?,
    val current: WeatherRegionUiState<CurrentWeather> = WeatherRegionUiState(),
    val forecast: WeatherRegionUiState<Forecast> = WeatherRegionUiState(),
    val hourly: WeatherRegionUiState<HourlyForecast> = WeatherRegionUiState(),
    val currentHour: Instant? = null,
    val today: LocalDate? = null,
    val language: String = "",
    val nameError: Boolean = false,
    val cooldownSeconds: Long = 0,
) {
    private val hasData: Boolean = current.data != null || forecast.data?.days?.any {
        it.maximumCelsius != null || it.minimumCelsius != null || it.weatherCode != null
    } == true || hourly.data?.hours?.any {
        it.temperatureCelsius != null || it.weatherCode != null
    } == true

    val showEmpty: Boolean = location == null || (
        !hasData && !current.loading && !forecast.loading && !hourly.loading &&
            (current.empty || current.error != null)
        )
    val retryEnabled: Boolean = cooldownSeconds == 0L
    val showCurrentLocationName: Boolean = location?.let {
        it.provider == DEVICE_LOCATION_PROVIDER && it.name.isBlank()
    } == true
    val todayForecast: DailyForecast? = forecast.data?.days?.firstOrNull { it.date == today }
    val dailyItems: List<DailyForecastItemUiState> = forecast.data?.days.orEmpty().map {
        DailyForecastItemUiState(it, it.date == today)
    }
    val hourlyItems: List<HourlyForecastItemUiState> = location?.let { place ->
        hourly.data?.hours.orEmpty().map {
            HourlyForecastItemUiState(it, place.timeZone, it.time == currentHour, today)
        }
    }.orEmpty()
}

// today 纳入 Diff，午夜刷新同一日期的标签也会重新绑定。
data class DailyForecastItemUiState(val day: DailyForecast, val today: Boolean)

/** 包含小时数据、地点时区和当前／今日标记，供列表直接展示。 */
data class HourlyForecastItemUiState(
    val hour: HourlyForecastEntry,
    val zone: ZoneId,
    val current: Boolean,
    val today: LocalDate?,
)
