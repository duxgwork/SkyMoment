package com.example.skymoment.domain

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

// 标识系统定位地点，名称解析走 Android Geocoder。
const val DEVICE_LOCATION_PROVIDER = "device"

/**
 * 校验有限且合法的经纬度，防止非法坐标进入请求链路。
 */
data class Coordinates(val latitude: Double, val longitude: Double) {
    init {
        // 纬度必须有限且位于南北极之间。
        require(latitude.isFinite() && latitude in -90.0..90.0)
        // 经度必须有限且位于合法经度范围。
        require(longitude.isFinite() && longitude in -180.0..180.0)
    }
}

/**
 * 将实际地点与首屏当前天气一起返回，便于后续页面复用。
 */
data class LocatedWeather(val location: Location, val current: CurrentWeather)

/**
 * 不实现 Parcelable/Serializable，防止地理信息进入 Intent 与系统恢复快照。
 */
data class Location(
    // 记录地点来源，区分设备定位与供应商搜索。
    val provider: String,
    // 保存来源内的地点标识，用于去重和身份校验。
    val id: String,
    // 保存展示名称，设备地点解析前可为空。
    val name: String,
    // 保存由细到粗的行政路径，帮助区分同名地点。
    val administrativePath: List<String>,
    // 保存天气查询使用的纬度。
    val latitude: Double,
    // 保存天气查询使用的经度。
    val longitude: Double,
    // 保存地点时区，用于预报日期和小时展示。
    val timeZone: ZoneId,
)

/**
 * 保存供应商名称与许可链接，和天气数值一起保留来源信息。
 */
data class WeatherAttribution(val text: String, val url: String?)

/**
 * 当前天气值和获取时刻；可空字段表示缺失，partial 标记部分可用。
 */
data class CurrentWeather(
    // 当前摄氏温度，缺失时由界面显示占位。
    val temperatureCelsius: Double?,
    // 相对湿度百分比，保留真实零值。
    val humidityPercent: Double?,
    // 每秒米制风速，缺失不等同于无风。
    val windMetresPerSecond: Double?,
    // 保留供应商天气代码，由展示层翻译。
    val weatherCode: Int?,
    // 昼夜标识，无法判断时保持为空。
    val isDay: Boolean?,
    // 记录客户端获取时间，用于时效判断。
    val fetchedAt: Instant,
    // 随天气保留数据来源和许可链接。
    val attributions: List<WeatherAttribution>,
    // 标记主要指标是否存在缺失。
    val partial: Boolean = false,
)

/**
 * 单日预报条目，日期使用地点日历，气温与天气码允许缺失。
 */
data class DailyForecast(
    // 保存地点当地日历日期。
    val date: LocalDate,
    // 当天最高摄氏温度，缺失时为空。
    val maximumCelsius: Double?,
    // 当天最低摄氏温度，缺失时为空。
    val minimumCelsius: Double?,
    // 当天概况代码，由展示层映射图标和文案。
    val weatherCode: Int?,
)

/**
 * 逐日预报集合及获取时刻，incomplete 表示窗口内容不完整。
 */
data class Forecast(
    // 保存当前日期窗口内的有效日预报。
    val days: List<DailyForecast>,
    // 记录这批日预报的获取时间。
    val fetchedAt: Instant,
    // 标记七日窗口是否缺少有效日期或指标记录。
    val incomplete: Boolean,
    // 保留日预报的数据来源信息。
    val attributions: List<WeatherAttribution>,
)

/**
 * 用绝对时刻表示单小时数据，避免夏令时重复小时产生歧义。
 */
data class HourlyForecastEntry(
    // 使用绝对时刻区分夏令时重复的当地小时。
    val time: Instant,
    // 该小时的摄氏温度，允许缺失。
    val temperatureCelsius: Double?,
    // 该小时的天气概况代码。
    val weatherCode: Int?,
    // 该小时的昼夜标识。
    val isDay: Boolean?,
)

/**
 * 小时预报集合和获取时刻，保留缺失时段和完整性标记。
 */
data class HourlyForecast(
    // 保存按窗口顺序排列的小时数据和空位。
    val hours: List<HourlyForecastEntry>,
    // 记录小时预报的获取时间。
    val fetchedAt: Instant,
    // 标记小时窗口中是否存在缺失温度或天气代码。
    val incomplete: Boolean,
    // 保留小时预报的数据来源信息。
    val attributions: List<WeatherAttribution>,
)

/**
 * 与平台和供应商无关的业务失败分类，供 ViewModel 和文案映射使用。
 */
enum class WeatherFailure {
    // 区分请求拒绝、网络、超时、鉴权及两类限额问题。
    REQUEST_REJECTED, NETWORK, TIMEOUT, AUTHENTICATION, QUOTA, RATE_LIMITED,

    // 区分服务不可用、数据错误和不支持坐标查询。
    SERVICE_UNAVAILABLE, DATA_INVALID, UNSUPPORTED_LOCATION,
}

/**
 * 统一表达成功、合法空数据和失败，避免用默认值伪装结果。
 */
sealed interface DataResult<out T> {
    /**
     * 携带成功结果的数据，调用方可以继续映射或展示。
     */
    data class Success<T>(val data: T) : DataResult<T>

    /**
     * 请求成功但没有可用业务数据，不等同于网络或格式错误。
     */
    data object Empty : DataResult<Nothing>

    /**
     * 携带失败原因以及可选的恢复时间，不抹掉其他区域的已有数据。
     */
    data class Failure(val reason: WeatherFailure, val retryAt: Instant? = null) :
    // 失败结果不包含成功数据，可用于任意业务结果类型。
        DataResult<Nothing>
}

/*
 * 只转换成功数据，保留空结果、失败原因与重试时间。
 */
inline fun <T, R> DataResult<T>.map(transform: (T) -> R): DataResult<R> = when (this) {
    // 仅对成功数据执行转换并保持成功语义。
    is DataResult.Success -> DataResult.Success(transform(data))
    // 原样传播失败原因和重试时间。
    is DataResult.Failure -> this
    // 原样传播合法空结果。
    DataResult.Empty -> DataResult.Empty
}
