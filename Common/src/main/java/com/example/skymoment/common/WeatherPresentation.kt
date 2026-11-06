package com.example.skymoment.common

import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** 统一天气码说明、图标和缺失占位，供各天气视图复用。 */
object WeatherPresentation {
    const val MISSING_VALUE = "--"

    // 把 WMO 天气代码映射为本地化说明，未知代码保持未知语义。
    fun description(code: Int?): Int = when (code) {
        0 -> R.string.weather_clear
        1 -> R.string.weather_mainly_clear
        2 -> R.string.weather_partly_cloudy
        3 -> R.string.weather_overcast
        45 -> R.string.weather_fog
        48 -> R.string.weather_rime_fog
        51 -> R.string.weather_light_drizzle
        53 -> R.string.weather_drizzle
        55 -> R.string.weather_heavy_drizzle
        56, 57 -> R.string.weather_freezing_drizzle
        61 -> R.string.weather_light_rain
        63 -> R.string.weather_rain
        65 -> R.string.weather_heavy_rain
        66, 67 -> R.string.weather_freezing_rain
        71 -> R.string.weather_light_snow
        73 -> R.string.weather_snow
        75 -> R.string.weather_heavy_snow
        77 -> R.string.weather_snow_grains
        80, 81 -> R.string.weather_showers
        82 -> R.string.weather_heavy_showers
        85, 86 -> R.string.weather_snow_showers
        95 -> R.string.weather_thunderstorm
        96, 99 -> R.string.weather_hail
        else -> R.string.weather_unknown
    }

    // 按天气代码和昼夜状态选择图标，缺失值使用统一占位。
    fun symbol(code: Int?, isDay: Boolean? = null): String = when (code) {
        0, 1 -> if (isDay == false) "☾" else "☀"
        2 -> "⛅"
        3, 45, 48 -> "☁"
        51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 80, 81, 82 -> "☂"
        71, 73, 75, 77, 85, 86 -> "❄"
        95, 96, 99 -> "ϟ"
        else -> "?"
    }
}

/** 根据显示语言格式化日期和数值，同时保持统一的公制单位。 */
class WeatherFormatter(private val locale: Locale) {
    // 按有效语言格式化有限数值，非法数值返回占位符。
    private fun number(value: Double?): String = value?.takeIf { it.isFinite() }?.let {
        NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = 1 }.format(it)
    } ?: WeatherPresentation.MISSING_VALUE

    // 格式化温度并附加摄氏单位，保留零值和负值。
    fun temperature(value: Double?) =
        if (value == null) WeatherPresentation.MISSING_VALUE else "${number(value)}°C"

    // 格式化相对湿度百分数，缺失值不伪造为零。
    fun humidity(value: Double?) =
        if (value == null) WeatherPresentation.MISSING_VALUE else "${number(value)}%"

    // 格式化风速并使用每秒米的统一单位。
    fun wind(value: Double?) =
        if (value == null) WeatherPresentation.MISSING_VALUE else "${number(value)} m/s"

    // 按显示语言格式化月日，供跨日列表与预报使用。
    fun day(date: LocalDate): String = date.format(DateTimeFormatter.ofPattern("MMM d", locale))
    // 按显示语言格式化星期缩写。
    fun weekday(date: LocalDate): String = date.format(DateTimeFormatter.ofPattern("EEE", locale))
    // 先转换到地点时区，再格式化小时标签。
    fun hour(time: Instant, zone: ZoneId): String =
        DateTimeFormatter.ofPattern("HH:mm", locale).withZone(zone).format(time)
}
