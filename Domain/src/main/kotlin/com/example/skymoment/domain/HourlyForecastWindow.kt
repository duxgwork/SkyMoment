package com.example.skymoment.domain

import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** 用绝对时间维护当地当前小时起的二十四小时窗口。 */
class HourlyForecastWindow(private val clock: Clock) {
    // 按地点当地整点确定小时窗口起点，并转回绝对时间。
    fun currentHour(zone: ZoneId): Instant =
        clock.instant().atZone(zone).truncatedTo(ChronoUnit.HOURS).toInstant()

    // 以当前当地整点为起点保留二十四个绝对小时，冲突或缺失时段保留空占位。
    fun retain(forecast: HourlyForecast, zone: ZoneId): HourlyForecast {
        val start = currentHour(zone)
        val groups = forecast.hours.groupBy { it.time }
        // 使用绝对时间推进，夏令时切换时仍保留连续的 24 个小时；缺失时段不向前挤占位置。
        val hours = (0L..23L).map { offset ->
            val time = start.plus(offset, ChronoUnit.HOURS)
            groups[time]?.distinct()?.singleOrNull() ?: HourlyForecastEntry(time, null, null, null)
        }
        return forecast.copy(
            hours = hours,
            incomplete = hours.any { it.temperatureCelsius == null || it.weatherCode == null })
    }
}
