package com.example.skymoment.domain

import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId

/**
 * 以地点当地日期保留今天起七天；同一天的冲突记录丢弃，不编造缺失预报。
 */
class ForecastWindow(private val clock: Clock) {
    /*
     * 使用地点时区和注入时钟计算今天，不依赖设备默认时区。
     */
    fun today(zone: ZoneId): LocalDate = LocalDate.now(clock.withZone(zone))

    /*
     * 保留当地今天起七日内的唯一日期，冲突日剔除并标记预报不完整。
     */
    fun retain(forecast: Forecast, zone: ZoneId): Forecast {
        // 以地点当地今天作为七日窗口起点。
        val start = today(zone)
        // 只保留今天到第六天后的日期。
        val groups = forecast.days.filter { it.date >= start && it.date <= start.plusDays(6) }
            // 按日期聚合重复的预报记录。
            .groupBy { it.date }
        // 准备去重并剔除冲突后的有效日期列表。
        val valid =
            // 相同记录合并，有冲突的日期舍弃，再按日期排序。
            groups.values.mapNotNull { rows -> rows.distinct().singleOrNull() }.sortedBy { it.date }
        // 更新日期列表，并将不足七天的窗口标记为不完整。
        return forecast.copy(days = valid, incomplete = forecast.incomplete || valid.size != 7)
    }
}
