package com.example.skymoment.datalayer.weather

import com.example.skymoment.domain.DataResult
import com.example.skymoment.domain.Location
import com.example.skymoment.domain.WeatherFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** 覆盖 OpenMeteoMapper 的正常流程、恢复行为与边界条件。 */
class OpenMeteoMapperTest {
    private val clock = Clock.fixed(Instant.parse("2026-11-05T02:00:00Z"), ZoneOffset.UTC)
    private val place = Location(
        "open-meteo",
        "1",
        "合成区",
        listOf("合成市"),
        30.0,
        120.0,
        ZoneId.of("Asia/Shanghai")
    )
    private val mapper = OpenMeteoMapper(clock)
    private val current = OpenMeteoForecastResponseDto(
        30.01, 120.01, "Asia/Shanghai",
        mapOf(
            "time" to "unixtime", "temperature_2m" to "°C", "relative_humidity_2m" to "%",
            "wind_speed_10m" to "m/s", "weather_code" to "wmo code"
        ),
        OpenMeteoCurrentWeatherDto(1, 0.0, 65.0, 0.0, 0, 1),
    )

    // 提供可控的逐日响应样本，隔离真实网络与系统环境。
    private fun daily() = OpenMeteoForecastResponseDto(
        latitude = 30.0, longitude = 120.0, timezone = "Asia/Shanghai",
        dailyUnits = mapOf(
            "time" to "iso8601",
            "temperature_2m_max" to "°C",
            "temperature_2m_min" to "°C",
            "weather_code" to "wmo code"
        ),
        daily = OpenMeteoDailyForecastDto(
            (5..11).map { "2026-11-%02d".format(it) },
            List(7) { 20.0 },
            List(7) { 10.0 },
            List(7) { 3 }),
    )

    @Test
    // 验证零数值、百分比和合法网格坐标被保留。
    fun percentZeroAndGridCoordinatesRemainValid() {
        val value = (mapper.current(current, place) as DataResult.Success).data
        assertEquals(65.0, value.humidityPercent)
        assertEquals(0.0, value.temperatureCelsius)
        assertEquals(0.0, value.windMetresPerSecond)
        assertEquals(clock.instant(), value.fetchedAt)
        assertFalse(value.partial)
    }

    @Test
    // 验证非法字段不破坏有效负温度等部分数据。
    fun invalidMetricsDoNotDestroyValidNegativeTemperature() {
        val result =
            mapper.current(current.copy(current = OpenMeteoCurrentWeatherDto(1, -5.0, 120.0, -1.0, 999, 1)), place)
        val value = (result as DataResult.Success).data
        assertEquals(-5.0, value.temperatureCelsius)
        assertNull(value.humidityPercent)
        assertNull(value.windMetresPerSecond)
        assertEquals(999, value.weatherCode)
        assertTrue(value.partial)
        val unknownUnit =
            current.copy(currentUnits = current.currentUnits!! + ("temperature_2m" to "unknown"))
        assertNull(
            (mapper.current(
                unknownUnit,
                place
            ) as DataResult.Success).data.temperatureCelsius
        )
    }

    @Test
    // 验证核心字段全缺失是空结果，响应结构或时区错误是非法数据。
    fun missingCoreMetricsAreEmptyButBrokenEnvelopeAndTimezoneAreErrors() {
        assertEquals(
            DataResult.Empty,
            mapper.current(current.copy(current = OpenMeteoCurrentWeatherDto(time = 1)), place)
        )
        assertEquals(
            DataResult.Failure(WeatherFailure.DATA_INVALID),
            mapper.current(OpenMeteoForecastResponseDto(), place)
        )
        assertEquals(
            DataResult.Failure(WeatherFailure.DATA_INVALID),
            mapper.current(current.copy(error = true), place)
        )
        assertEquals(
            DataResult.Failure(WeatherFailure.DATA_INVALID),
            mapper.current(current.copy(timezone = "No/SuchZone"), place)
        )
    }

    @Test
    // 验证逐日平行数组必须对齐，最高低于最低温时按缺失处理。
    fun dailyArraysMustAlignAndInvertedTemperaturesAreMissing() {
        val response = daily()
        assertEquals(7, (mapper.forecast(response, place) as DataResult.Success).data.days.size)
        assertEquals(
            DataResult.Failure(WeatherFailure.DATA_INVALID),
            mapper.forecast(
                response.copy(daily = response.daily!!.copy(maximum = listOf(20.0))),
                place
            )
        )
        val inverted = response.copy(daily = response.daily!!.copy(maximum = List(7) { 5.0 }))
        val day = (mapper.forecast(inverted, place) as DataResult.Success).data.days.first()
        assertNull(day.maximumCelsius)
        assertNull(day.minimumCelsius)
        assertEquals(3, day.weatherCode)
    }

    @Test
    // 验证小时单位、数组与缺失值校验不会导致时段错位。
    fun hourlyValidatesUnitsArrayAlignmentAndMissingValuesWithoutShiftingHours() {
        val response = OpenMeteoForecastResponseDto(
            latitude = 30.0, longitude = 120.0, timezone = "Asia/Shanghai",
            hourlyUnits = mapOf(
                "time" to "unixtime",
                "temperature_2m" to "°C",
                "weather_code" to "wmo code"
            ),
            hourly = OpenMeteoHourlyForecastDto(
                (0L..23).map { clock.instant().plusSeconds(it * 3600).epochSecond },
                List(24) { if (it == 1) null else -2.0 }, List(24) { 0 }, List(24) { 0 })
        )
        val result = (mapper.hourly(response, place) as DataResult.Success).data
        assertEquals(24, result.hours.size)
        assertEquals(-2.0, result.hours[0].temperatureCelsius)
        assertEquals(false, result.hours[0].isDay)
        assertNull(result.hours[1].temperatureCelsius)
        assertEquals(clock.instant().plusSeconds(7200), result.hours[2].time)
        assertTrue(result.incomplete)
        assertEquals(
            DataResult.Failure(WeatherFailure.DATA_INVALID),
            mapper.hourly(response.copy(hourly = response.hourly!!.copy(codes = listOf(0))), place)
        )
        assertEquals(
            DataResult.Failure(WeatherFailure.DATA_INVALID),
            mapper.hourly(response.copy(hourlyUnits = mapOf("time" to "iso8601")), place)
        )
        val wrongTemperatureUnit =
            response.copy(hourlyUnits = response.hourlyUnits!! + ("temperature_2m" to "°F"))
        assertTrue(
            (mapper.hourly(
                wrongTemperatureUnit,
                place
            ) as DataResult.Success).data.hours.all { it.temperatureCelsius == null })
        assertEquals(
            DataResult.Empty, mapper.hourly(
                response.copy(
                    hourly = response.hourly!!.copy(
                        temperatures = List(24) { null }, codes = List(24) { null })
                ), place
            )
        )
    }

    @Test
    // 验证地点映射保留业务标识并拒绝非法时区。
    fun geoMappingKeepsIdentityAndRejectsInvalidTimezone() {
        val geo = OpenMeteoLocationDto(
            1,
            "合成区",
            30.0,
            120.0,
            "Asia/Shanghai",
            "中国",
            "CN",
            "合成省",
            "合成市",
            "合成区"
        )
        val mapped = mapper.location(geo)!!
        assertEquals("1", mapped.id)
        assertEquals(listOf("合成市", "合成省", "中国"), mapped.administrativePath)
        assertNull(mapper.location(geo.copy(timezone = "invalid")))
        assertNull(mapper.location(geo.copy(latitude = 100.0)))
        assertNull(mapper.location(geo.copy(error = true)))
    }
}
