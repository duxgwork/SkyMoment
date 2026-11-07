package com.example.skymoment.datalayer.weather

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 地点搜索原始响应；缺失字段留空，交由映射器判断合法空结果与错误。
 * 为地点搜索响应生成 JSON 序列化适配器。
 */
@Serializable
data class OpenMeteoGeocodingResponseDto(
    // 保留原始候选列表，缺失与空列表由仓储区分。
    val results: List<OpenMeteoLocationDto>? = null,
    // 映射供应商处理耗时，辅助识别合法空响应。
    @SerialName("generationtime_ms") val generationTime: Double? = null,
    // 保留供应商错误标记，缺省视为未声明错误。
    val error: Boolean = false,
)

/**
 * 供应商地点字段，保留原始可空值供边界校验。
 * 为单个地点生成 JSON 序列化适配器。
 */
@Serializable
data class OpenMeteoLocationDto(
    // 接收供应商的数值地点 ID。
    val id: Long? = null,
    // 接收本地化地点名称。
    val name: String? = null,
    // 接收原始纬度，后续统一检查范围。
    val latitude: Double? = null,
    // 接收原始经度，后续统一检查范围。
    val longitude: Double? = null,
    // 接收时区名称，后续转换为 ZoneId。
    val timezone: String? = null,
    // 接收国家展示名称。
    val country: String? = null,
    // 将 country_code 映射为国家代码字段。
    @SerialName("country_code") val countryCode: String? = null,
    // 接收一级行政区名称。
    val admin1: String? = null,
    // 接收二级行政区名称。
    val admin2: String? = null,
    // 接收三级行政区名称。
    val admin3: String? = null,
    // 接收四级行政区名称。
    val admin4: String? = null,
    // 标识该地点对象是否为错误响应。
    val error: Boolean = false,
)

/**
 * 天气原始响应，分别保存单位、当前、逐日和逐小时字段。
 * 为共用天气响应生成 JSON 序列化适配器。
 */
@Serializable
data class OpenMeteoForecastResponseDto(
    // 接收供应商返回的网格纬度。
    val latitude: Double? = null,
    // 接收供应商返回的网格经度。
    val longitude: Double? = null,
    // 接收响应使用的地点时区。
    val timezone: String? = null,
    // 保存当前天气各字段的单位标签。
    @SerialName("current_units") val currentUnits: Map<String, String>? = null,
    // 保存当前天气的原始指标。
    val current: OpenMeteoCurrentWeatherDto? = null,
    // 保存逐日字段的单位标签。
    @SerialName("daily_units") val dailyUnits: Map<String, String>? = null,
    // 保存逐日平行数组。
    val daily: OpenMeteoDailyForecastDto? = null,
    // 保留整个天气响应的错误标记。
    val error: Boolean = false,
    // 保存逐小时字段的单位标签。
    @SerialName("hourly_units") val hourlyUnits: Map<String, String>? = null,
    // 保存逐小时平行数组。
    val hourly: OpenMeteoHourlyForecastDto? = null,
)

/**
 * 当前天气传输字段，SerialName 保持供应商 JSON 协议不变。
 * 为当前天气字段生成序列化适配器。
 */
@Serializable
data class OpenMeteoCurrentWeatherDto(
    // 接收当前天气有效时刻的 Unix 秒。
    val time: Long? = null,
    // 映射两米高度温度，暂不填充缺失值。
    @SerialName("temperature_2m") val temperature: Double? = null,
    // 映射两米高度相对湿度。
    @SerialName("relative_humidity_2m") val humidity: Double? = null,
    // 映射十米高度风速。
    @SerialName("wind_speed_10m") val wind: Double? = null,
    // 映射天气概况代码。
    @SerialName("weather_code") val code: Int? = null,
    // 映射昼夜数值标志，后续只接受零或一。
    @SerialName("is_day") val isDay: Int? = null,
)

/**
 * 逐日平行数组传输结构，日期与各指标必须按相同索引对齐。
 * 为逐日预报生成序列化适配器。
 */
@Serializable
data class OpenMeteoDailyForecastDto(
    // 接收当地日期字符串序列。
    val time: List<String>? = null,
    // 接收各日期最高温，保留单项空值。
    @SerialName("temperature_2m_max") val maximum: List<Double?>? = null,
    // 接收各日期最低温，保留单项空值。
    @SerialName("temperature_2m_min") val minimum: List<Double?>? = null,
    // 接收各日期天气代码，保持索引对应。
    @SerialName("weather_code") val codes: List<Int?>? = null,
)

/**
 * 逐小时平行数组传输结构，时间使用 Unix 秒避免时区歧义。
 * 为逐小时预报生成序列化适配器。
 */
@Serializable
data class OpenMeteoHourlyForecastDto(
    // 接收每个小时的 Unix 秒，保留非法或缺失时间供校验。
    val time: List<Long?>? = null,
    // 接收小时温度，保持与时间数组对齐。
    @SerialName("temperature_2m") val temperatures: List<Double?>? = null,
    // 接收小时天气代码，保持与时间数组对齐。
    @SerialName("weather_code") val codes: List<Int?>? = null,
    // 接收小时昼夜标识，保持与时间数组对齐。
    @SerialName("is_day") val isDay: List<Int?>? = null,
)
