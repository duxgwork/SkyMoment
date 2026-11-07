package com.example.skymoment.common

import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 统一天气码说明、图标和缺失占位，供各天气视图复用。
 */
object WeatherPresentation {
    // 统一所有缺失天气数值的展示占位。
    const val MISSING_VALUE = "--"

    /*
     * 把 WMO 天气代码映射为本地化说明，未知代码保持未知语义。
     */
    fun description(code: Int?): Int = when (code) {
        // 天气代码零对应晴朗。
        0 -> R.string.weather_clear
        // 天气代码一对应大部晴朗。
        1 -> R.string.weather_mainly_clear
        // 天气代码二对应局部多云。
        2 -> R.string.weather_partly_cloudy
        // 天气代码三对应阴天。
        3 -> R.string.weather_overcast
        // 天气代码四十五对应雾。
        45 -> R.string.weather_fog
        // 天气代码四十八对应雾凇。
        48 -> R.string.weather_rime_fog
        // 天气代码五十一对应小毛毛雨。
        51 -> R.string.weather_light_drizzle
        // 天气代码五十三对应毛毛雨。
        53 -> R.string.weather_drizzle
        // 天气代码五十五对应强毛毛雨。
        55 -> R.string.weather_heavy_drizzle
        // 将两档冻毛毛雨合并为统一描述。
        56, 57 -> R.string.weather_freezing_drizzle
        // 天气代码六十一对应小雨。
        61 -> R.string.weather_light_rain
        // 天气代码六十三对应雨。
        63 -> R.string.weather_rain
        // 天气代码六十五对应大雨。
        65 -> R.string.weather_heavy_rain
        // 将两档冻雨合并为统一描述。
        66, 67 -> R.string.weather_freezing_rain
        // 天气代码七十一对应小雪。
        71 -> R.string.weather_light_snow
        // 天气代码七十三对应雪。
        73 -> R.string.weather_snow
        // 天气代码七十五对应大雪。
        75 -> R.string.weather_heavy_snow
        // 天气代码七十七对应雪粒。
        77 -> R.string.weather_snow_grains
        // 将普通阵雨强度合并为阵雨描述。
        80, 81 -> R.string.weather_showers
        // 天气代码八十二对应强阵雨。
        82 -> R.string.weather_heavy_showers
        // 将两档阵雪合并为统一描述。
        85, 86 -> R.string.weather_snow_showers
        // 天气代码九十五对应雷暴。
        95 -> R.string.weather_thunderstorm
        // 将带冰雹的雷暴代码归为冰雹描述。
        96, 99 -> R.string.weather_hail
        // 未知或缺失代码使用未知天气文案。
        else -> R.string.weather_unknown
    }

    /*
     * 按天气代码和昼夜状态选择图标，缺失值使用统一占位。
     */
    fun symbol(code: Int?, isDay: Boolean? = null): String = when (code) {
        // 晴朗与大部晴朗根据昼夜选择月亮或太阳符号。
        0, 1 -> if (isDay == false) "☾" else "☀"
        // 局部多云使用太阳与云的组合符号。
        2 -> "⛅"
        // 阴天和雾类天气统一使用云符号。
        3, 45, 48 -> "☁"
        // 毛毛雨、降雨、冻雨及阵雨使用雨伞符号。
        51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 80, 81, 82 -> "☂"
        // 降雪、雪粒和阵雪使用雪花符号。
        71, 73, 75, 77, 85, 86 -> "❄"
        // 雷暴及带冰雹天气使用闪电符号。
        95, 96, 99 -> "ϟ"
        // 无法映射时保留问号，避免伪造天气状态。
        else -> "?"
    }
}

/**
 * 根据显示语言格式化日期和数值，同时保持统一的公制单位。
 */
class WeatherFormatter(private val locale: Locale) {
    /*
     * 按有效语言格式化有限数值，非法数值返回占位符。
     */
    private fun number(value: Double?): String = value?.takeIf { it.isFinite() }?.let {
        // 按界面语言格式化数值，最多保留一位小数。
        NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = 1 }.format(it)
        // 没有有效数值时使用统一缺失占位。
    } ?: WeatherPresentation.MISSING_VALUE

    /*
     * 格式化温度并附加摄氏单位，保留零值和负值。
     */
    fun temperature(value: Double?) =
        // 仅在有温度时附加摄氏单位，避免给缺失值添加单位。
        if (value == null) WeatherPresentation.MISSING_VALUE else "${number(value)}°C"

    /*
     * 格式化相对湿度百分数，缺失值不伪造为零。
     */
    fun humidity(value: Double?) =
        // 仅在有湿度时附加百分号，零湿度仍正常显示。
        if (value == null) WeatherPresentation.MISSING_VALUE else "${number(value)}%"

    /*
     * 格式化风速并使用每秒米的统一单位。
     */
    fun wind(value: Double?) =
        // 仅在有风速时附加米每秒单位。
        if (value == null) WeatherPresentation.MISSING_VALUE else "${number(value)} m/s"

    /*
     * 按显示语言格式化月日，供跨日列表与预报使用。
     */
    fun day(date: LocalDate): String = date.format(DateTimeFormatter.ofPattern("MMM d", locale))

    /*
     * 按显示语言格式化星期缩写。
     */
    fun weekday(date: LocalDate): String = date.format(DateTimeFormatter.ofPattern("EEE", locale))

    /*
     * 先转换到地点时区，再格式化小时标签。
     */
    fun hour(time: Instant, zone: ZoneId): String =
        // 按地点时区和界面语言显示二十四小时制获取时间。
        DateTimeFormatter.ofPattern("HH:mm", locale).withZone(zone).format(time)
}
