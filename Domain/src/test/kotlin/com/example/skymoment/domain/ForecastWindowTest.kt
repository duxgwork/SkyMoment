package com.example.skymoment.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/** 覆盖 ForecastWindow 的正常流程、恢复行为与边界条件。 */
class ForecastWindowTest {
    // 提供可控的七日预报样本，隔离真实网络与系统环境。
    private fun forecast(start: LocalDate, days: Int = 7) = Forecast(
        (0 until days).map { DailyForecast(start.plusDays(it.toLong()), 20.0, 10.0, 3) },
        Instant.EPOCH, false, emptyList(),
    )

    @Test
    // 验证日历窗口使用地点时区并兼容夏令时。
    fun locationDateOverridesDeviceZoneAndSurvivesDst() {
        val clock = Clock.fixed(Instant.parse("2026-11-04T17:00:00Z"), ZoneOffset.UTC)
        val window = ForecastWindow(clock)
        assertEquals(LocalDate.parse("2026-11-05"), window.today(ZoneId.of("Asia/Shanghai")))
        assertEquals(LocalDate.parse("2026-11-04"), window.today(ZoneId.of("America/New_York")))
        val dst = ForecastWindow(Clock.fixed(Instant.parse("2026-10-30T12:00:00Z"), ZoneOffset.UTC))
        val seven =
            dst.retain(forecast(LocalDate.parse("2026-10-30")), ZoneId.of("America/New_York"))
        assertFalse(seven.incomplete)
        assertEquals(LocalDate.parse("2026-11-05"), seven.days.last().date)
    }

    @Test
    // 验证午夜剔除昨天，冲突日期不制造默认预报。
    fun midnightRemovesYesterdayAndConflictingDuplicateLeavesGap() {
        val start = LocalDate.parse("2026-11-05")
        val zone = ZoneId.of("Asia/Shanghai")
        val before =
            ForecastWindow(Clock.fixed(Instant.parse("2026-11-05T15:59:59Z"), ZoneOffset.UTC))
        val original = forecast(start)
        assertFalse(before.retain(original, zone).incomplete)
        val after =
            ForecastWindow(Clock.fixed(Instant.parse("2026-11-05T16:00:00Z"), ZoneOffset.UTC))
        val remaining = after.retain(original, zone)
        assertEquals(6, remaining.days.size)
        assertEquals(start.plusDays(1), remaining.days.first().date)
        assertTrue(remaining.incomplete)
        val mixed = original.copy(
            days = original.days.reversed() + original.days.first().copy(weatherCode = 61)
        )
        assertEquals(6, before.retain(mixed, zone).days.size)
        val identical = original.copy(days = original.days + original.days.first())
        assertEquals(7, before.retain(identical, zone).days.size)
        assertTrue(before.retain(forecast(start.minusDays(10)), zone).days.isEmpty())
    }
}
