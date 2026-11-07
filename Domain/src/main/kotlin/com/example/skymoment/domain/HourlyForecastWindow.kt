package com.example.skymoment.domain

import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * 用绝对时间维护当地当前小时起的二十四小时窗口。
 */
class HourlyForecastWindow(private val clock: Clock) {
    /*
     * 按地点当地整点确定小时窗口起点，并转回绝对时间。
     */
    fun currentHour(zone: ZoneId): Instant =
        // 先按地点时区截断到整点，再转换为绝对时刻。
        clock.instant().atZone(zone).truncatedTo(ChronoUnit.HOURS).toInstant()

    /*
     * 以当前当地整点为起点保留二十四个绝对小时，冲突或缺失时段保留空占位。
     */
    fun retain(forecast: HourlyForecast, zone: ZoneId): HourlyForecast {
        // 计算本次小时窗口的起始时刻。
        val start = currentHour(zone)
        // 将同一绝对时刻的候选记录聚合。
        val groups = forecast.hours.groupBy { it.time }
        // 使用绝对时间推进，夏令时切换时仍保留连续的 24 个小时；缺失时段不向前挤占位置。
        val hours = (0L..23L).map { offset ->
            // 按绝对小时递增，跨夏令时仍保持连续时间。
            val time = start.plus(offset, ChronoUnit.HOURS)
            // 复用唯一有效记录，缺失或冲突时在原位置填空项。
            groups[time]?.distinct()?.singleOrNull() ?: HourlyForecastEntry(time, null, null, null)
        }
        // 基于原响应保留获取时间和来源元数据。
        return forecast.copy(
            // 用连续二十四个小时的位置替换原始列表。
            hours = hours,
            // 任一小时缺少温度或天气代码即标记为不完整。
            incomplete = hours.any { it.temperatureCelsius == null || it.weatherCode == null })
    }
}
