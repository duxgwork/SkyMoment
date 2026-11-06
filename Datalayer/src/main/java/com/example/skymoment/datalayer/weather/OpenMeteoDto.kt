package com.example.skymoment.datalayer.weather

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
/** 地点搜索原始响应；缺失字段留空，交由映射器判断合法空结果与错误。 */
data class OpenMeteoGeocodingResponseDto(
    val results: List<OpenMeteoLocationDto>? = null,
    @SerialName("generationtime_ms") val generationTime: Double? = null,
    val error: Boolean = false,
)

@Serializable
/** 供应商地点字段，保留原始可空值供边界校验。 */
data class OpenMeteoLocationDto(
    val id: Long? = null,
    val name: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val timezone: String? = null,
    val country: String? = null,
    @SerialName("country_code") val countryCode: String? = null,
    val admin1: String? = null,
    val admin2: String? = null,
    val admin3: String? = null,
    val admin4: String? = null,
    val error: Boolean = false,
)

@Serializable
/** 天气原始响应，分别保存单位、当前、逐日和逐小时字段。 */
data class OpenMeteoForecastResponseDto(
    val latitude: Double? = null,
    val longitude: Double? = null,
    val timezone: String? = null,
    @SerialName("current_units") val currentUnits: Map<String, String>? = null,
    val current: OpenMeteoCurrentWeatherDto? = null,
    @SerialName("daily_units") val dailyUnits: Map<String, String>? = null,
    val daily: OpenMeteoDailyForecastDto? = null,
    val error: Boolean = false,
    @SerialName("hourly_units") val hourlyUnits: Map<String, String>? = null,
    val hourly: OpenMeteoHourlyForecastDto? = null,
)

@Serializable
/** 当前天气传输字段，SerialName 保持供应商 JSON 协议不变。 */
data class OpenMeteoCurrentWeatherDto(
    val time: Long? = null,
    @SerialName("temperature_2m") val temperature: Double? = null,
    @SerialName("relative_humidity_2m") val humidity: Double? = null,
    @SerialName("wind_speed_10m") val wind: Double? = null,
    @SerialName("weather_code") val code: Int? = null,
    @SerialName("is_day") val isDay: Int? = null,
)

@Serializable
/** 逐日平行数组传输结构，日期与各指标必须按相同索引对齐。 */
data class OpenMeteoDailyForecastDto(
    val time: List<String>? = null,
    @SerialName("temperature_2m_max") val maximum: List<Double?>? = null,
    @SerialName("temperature_2m_min") val minimum: List<Double?>? = null,
    @SerialName("weather_code") val codes: List<Int?>? = null,
)

@Serializable
/** 逐小时平行数组传输结构，时间使用 Unix 秒避免时区歧义。 */
data class OpenMeteoHourlyForecastDto(
    val time: List<Long?>? = null,
    @SerialName("temperature_2m") val temperatures: List<Double?>? = null,
    @SerialName("weather_code") val codes: List<Int?>? = null,
    @SerialName("is_day") val isDay: List<Int?>? = null,
)
