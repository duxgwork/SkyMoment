package com.example.skymoment.domain

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

const val DEVICE_LOCATION_PROVIDER = "device"

/** 校验有限且合法的经纬度，防止非法坐标进入请求链路。 */
data class Coordinates(val latitude: Double, val longitude: Double) {
    init {
        require(latitude.isFinite() && latitude in -90.0..90.0)
        require(longitude.isFinite() && longitude in -180.0..180.0)
    }
}

/** 将实际地点与首屏当前天气一起返回，便于后续页面复用。 */
data class LocatedWeather(val location: Location, val current: CurrentWeather)

// 不实现 Parcelable/Serializable，防止地理信息进入 Intent 与系统恢复快照。
data class Location(
    val provider: String,
    val id: String,
    val name: String,
    val administrativePath: List<String>,
    val latitude: Double,
    val longitude: Double,
    val timeZone: ZoneId,
)

/** 保存供应商名称与许可链接，和天气数值一起保留来源信息。 */
data class WeatherAttribution(val text: String, val url: String?)
/** 当前天气值和获取时刻；可空字段表示缺失，partial 标记部分可用。 */
data class CurrentWeather(
    val temperatureCelsius: Double?,
    val humidityPercent: Double?,
    val windMetresPerSecond: Double?,
    val weatherCode: Int?,
    val isDay: Boolean?,
    val fetchedAt: Instant,
    val attributions: List<WeatherAttribution>,
    val partial: Boolean = false,
)

/** 单日预报条目，日期使用地点日历，气温与天气码允许缺失。 */
data class DailyForecast(
    val date: LocalDate,
    val maximumCelsius: Double?,
    val minimumCelsius: Double?,
    val weatherCode: Int?,
)

/** 逐日预报集合及获取时刻，incomplete 表示窗口内容不完整。 */
data class Forecast(
    val days: List<DailyForecast>,
    val fetchedAt: Instant,
    val incomplete: Boolean,
    val attributions: List<WeatherAttribution>,
)

/** 用绝对时刻表示单小时数据，避免夏令时重复小时产生歧义。 */
data class HourlyForecastEntry(
    val time: Instant,
    val temperatureCelsius: Double?,
    val weatherCode: Int?,
    val isDay: Boolean?,
)

/** 小时预报集合和获取时刻，保留缺失时段和完整性标记。 */
data class HourlyForecast(
    val hours: List<HourlyForecastEntry>,
    val fetchedAt: Instant,
    val incomplete: Boolean,
    val attributions: List<WeatherAttribution>,
)

/** 与平台和供应商无关的业务失败分类，供 ViewModel 和文案映射使用。 */
enum class WeatherFailure {
    REQUEST_REJECTED, NETWORK, TIMEOUT, AUTHENTICATION, QUOTA, RATE_LIMITED,
    SERVICE_UNAVAILABLE, DATA_INVALID, UNSUPPORTED_LOCATION,
}

/** 统一表达成功、合法空数据和失败，避免用默认值伪装结果。 */
sealed interface DataResult<out T> {
    /** 携带成功结果的数据，调用方可以继续映射或展示。 */
    data class Success<T>(val data: T) : DataResult<T>
    /** 请求成功但没有可用业务数据，不等同于网络或格式错误。 */
    data object Empty : DataResult<Nothing>
    /** 携带失败原因以及可选的恢复时间，不抹掉其他区域的已有数据。 */
    data class Failure(val reason: WeatherFailure, val retryAt: Instant? = null) :
        DataResult<Nothing>
}

/** 只转换成功数据，保留空结果、失败原因与重试时间。 */
inline fun <T, R> DataResult<T>.map(transform: (T) -> R): DataResult<R> = when (this) {
    is DataResult.Success -> DataResult.Success(transform(data))
    is DataResult.Failure -> this
    DataResult.Empty -> DataResult.Empty
}
