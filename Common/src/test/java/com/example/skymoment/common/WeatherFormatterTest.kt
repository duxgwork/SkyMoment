package com.example.skymoment.common

import com.example.skymoment.framework.UiLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** 覆盖 WeatherFormatter 的正常流程、恢复行为与边界条件。 */
class WeatherFormatterTest {
    @Test
    // 验证各语言保留统一单位、零值和缺失占位。
    fun allLanguagesKeepMetricUnitsZeroAndMissingValues() {
        UiLanguage.entries.forEach { language ->
            val formatter = WeatherFormatter(language.locale)
            assertEquals("0°C", formatter.temperature(0.0))
            assertEquals("-2.5°C", formatter.temperature(-2.5))
            assertEquals("65%", formatter.humidity(65.0))
            assertEquals("0 m/s", formatter.wind(0.0))
            assertEquals("--", formatter.temperature(null))
            assertTrue(formatter.day(LocalDate.parse("2026-11-05")).contains("5"))
            assertTrue(formatter.weekday(LocalDate.parse("2026-11-05")).isNotEmpty())
        }
    }

    @Test
    // 验证未知天气码不假装晴天，晴朗夜间使用月亮图标。
    fun unknownCodeDoesNotMasqueradeAsClearWeatherAndNightUsesMoon() {
        assertEquals(R.string.weather_unknown, WeatherPresentation.description(999))
        assertEquals("?", WeatherPresentation.symbol(null))
        assertNotEquals(WeatherPresentation.symbol(0, true), WeatherPresentation.symbol(0, false))
    }
}
