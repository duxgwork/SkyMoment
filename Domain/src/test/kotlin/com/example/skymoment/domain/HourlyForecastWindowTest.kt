package com.example.skymoment.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/** 覆盖 HourlyForecastWindow 的正常流程、恢复行为与边界条件。 */
class HourlyForecastWindowTest {
    @Test
    // 验证小时窗口从当地整点连续跨日保留二十四小时。
    fun keepsCurrentLocalHourThroughNext23HoursAcrossMidnight() {
        val now = Instant.parse("2026-11-05T15:37:00Z")
        val start = Instant.parse("2026-11-05T15:00:00Z")
        val zone = ZoneId.of("Asia/Shanghai")
        val window = HourlyForecastWindow(Clock.fixed(now, ZoneOffset.UTC))
        val rows = (-2L..26).map {
            HourlyForecastEntry(
                start.plusSeconds(it * 3600),
                it.toDouble(),
                3,
                false
            )
        }
        val forecast = window.retain(HourlyForecast(rows.reversed(), now, false, emptyList()), zone)
        assertEquals(24, forecast.hours.size)
        assertEquals(start, forecast.hours.first().time)
        assertEquals(start.plusSeconds(23 * 3600), forecast.hours.last().time)
        assertEquals(
            LocalDate.parse("2026-11-06"),
            forecast.hours[1].time.atZone(zone).toLocalDate()
        )
        assertFalse(forecast.incomplete)
    }

    @Test
    // 验证缺失和冲突小时保留占位，不使后续小时错位。
    fun gapsAndConflictingDuplicatesKeepTheirHourAsMissing() {
        val now = Instant.parse("2026-11-05T10:30:00Z")
        val start = Instant.parse("2026-11-05T10:00:00Z")
        val rows = (0L..23).map {
            HourlyForecastEntry(
                start.plusSeconds(it * 3600),
                it.toDouble(),
                3,
                true
            )
        }
        val input =
            rows.filterIndexed { index, _ -> index != 1 } + rows[2].copy(temperatureCelsius = -20.0)
        val result = HourlyForecastWindow(Clock.fixed(now, ZoneOffset.UTC))
            .retain(HourlyForecast(input, now, false, emptyList()), ZoneOffset.UTC)
        assertEquals(24, result.hours.size)
        assertNull(result.hours[1].temperatureCelsius)
        assertNull(result.hours[2].temperatureCelsius)
        assertEquals(3.0, result.hours[3].temperatureCelsius)
        assertTrue(result.incomplete)
    }

    @Test
    // 验证夏令时重复小时保持两个绝对时刻，非整时时区也从当地整点开始。
    fun repeatedDstHourRetainsBothDistinctInstantsAndFractionalZonesStartLocally() {
        val start = Instant.parse("2026-11-01T05:00:00Z")
        val zone = ZoneId.of("America/New_York")
        val window = HourlyForecastWindow(Clock.fixed(start.plusSeconds(1800), ZoneOffset.UTC))
        val rows = (0L..23).map { HourlyForecastEntry(start.plusSeconds(it * 3600), 10.0, 0, true) }
        val result = window.retain(HourlyForecast(rows, start, false, emptyList()), zone)
        assertEquals(24, result.hours.map { it.time }.distinct().size)
        assertEquals(1, result.hours[0].time.atZone(zone).hour)
        assertEquals(1, result.hours[1].time.atZone(zone).hour)
        assertFalse(result.incomplete)
        assertEquals(
            Instant.parse("2026-11-01T05:15:00Z"),
            window.currentHour(ZoneId.of("Asia/Kathmandu"))
        )
    }
}
