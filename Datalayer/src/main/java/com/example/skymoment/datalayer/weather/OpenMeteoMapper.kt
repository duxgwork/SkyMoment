package com.example.skymoment.datalayer.weather

import com.example.skymoment.domain.CurrentWeather
import com.example.skymoment.domain.DailyForecast
import com.example.skymoment.domain.DataResult
import com.example.skymoment.domain.Forecast
import com.example.skymoment.domain.ForecastWindow
import com.example.skymoment.domain.HourlyForecast
import com.example.skymoment.domain.HourlyForecastEntry
import com.example.skymoment.domain.HourlyForecastWindow
import com.example.skymoment.domain.Location
import com.example.skymoment.domain.WeatherAttribution
import com.example.skymoment.domain.WeatherFailure
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** 统一保留天气数据供应商及许可来源，不在页面重复定义。 */
object OpenMeteoAttributions {
    val weather = listOf(
        WeatherAttribution("Open-Meteo", "https://open-meteo.com/"),
        WeatherAttribution("CC BY 4.0", "https://creativecommons.org/licenses/by/4.0/"),
    )
}

/** 在数据边界校验供应商单位、时区与平行数组；单项缺失保留为 null 供界面展示占位。 */
class OpenMeteoMapper(private val clock: Clock) {
    // 校验标识、坐标和时区，并拼接不重复的行政路径；无效地点直接丢弃。
    fun location(dto: OpenMeteoLocationDto): Location? {
        if (dto.error || dto.id == null || dto.name.isNullOrBlank()) return null
        val latitude = dto.latitude?.takeIf { it.isFinite() && it in -90.0..90.0 } ?: return null
        val longitude = dto.longitude?.takeIf { it.isFinite() && it in -180.0..180.0 } ?: return null
        val zone = runCatching { ZoneId.of(dto.timezone) }.getOrNull() ?: return null
        val path = listOfNotNull(dto.admin4, dto.admin3, dto.admin2, dto.admin1, dto.country ?: dto.countryCode)
            .filter { it.isNotBlank() && it != dto.name }.distinct()
        return Location("open-meteo", dto.id.toString(), dto.name, path, latitude, longitude, zone)
    }

    // 逐项校验当前天气单位和取值，保留部分有效字段并区分全空与非法响应。
    fun current(dto: OpenMeteoForecastResponseDto, location: Location): DataResult<CurrentWeather> {
        if (!validEnvelope(dto, location)) return invalid()
        val value = dto.current ?: return invalid()
        val units = dto.currentUnits ?: return invalid()
        if (value.time == null || units["time"] != "unixtime") return invalid()
        val temperature = value.temperature?.takeIf { units["temperature_2m"] == "°C" && it.isFinite() }
        val humidity = value.humidity?.takeIf { units["relative_humidity_2m"] == "%" && it.isFinite() && it in 0.0..100.0 }
        val wind = value.wind?.takeIf { units["wind_speed_10m"] == "m/s" && it.isFinite() && it >= 0 }
        val code = value.code?.takeIf { units["weather_code"] == "wmo code" && it >= 0 }
        if (temperature == null && humidity == null && wind == null && code == null) return DataResult.Empty
        return DataResult.Success(CurrentWeather(
            temperature, humidity, wind, code, value.isDay?.takeIf { it in 0..1 }?.let { it == 1 },
            clock.instant(), OpenMeteoAttributions.weather, temperature == null || humidity == null || wind == null || code == null,
        ))
    }

    // 校验日预报平行数组和温度范围，再裁剪为当地七日窗口。
    fun forecast(dto: OpenMeteoForecastResponseDto, location: Location): DataResult<Forecast> {
        if (!validEnvelope(dto, location)) return invalid()
        val days = dto.daily ?: return invalid()
        val dates = days.time ?: return invalid()
        val units = dto.dailyUnits ?: return invalid()
        if (units["time"] != "iso8601") return invalid()
        if (listOfNotNull(days.maximum, days.minimum, days.codes).any { it.size != dates.size }) return invalid()
        if (dates.isEmpty()) return DataResult.Empty
        val rows = dates.mapIndexedNotNull { index, rawDate ->
            val date = runCatching { LocalDate.parse(rawDate) }.getOrNull() ?: return@mapIndexedNotNull null
            var maximum = days.maximum?.get(index)?.takeIf { units["temperature_2m_max"] == "°C" && it.isFinite() }
            var minimum = days.minimum?.get(index)?.takeIf { units["temperature_2m_min"] == "°C" && it.isFinite() }
            if (maximum != null && minimum != null && maximum < minimum) { maximum = null; minimum = null }
            val code = days.codes?.get(index)?.takeIf { units["weather_code"] == "wmo code" && it >= 0 }
            if (maximum == null && minimum == null && code == null) return@mapIndexedNotNull null
            DailyForecast(date, maximum, minimum, code)
        }
        val result = ForecastWindow(clock).retain(Forecast(rows, clock.instant(), rows.size != dates.size, OpenMeteoAttributions.weather), location.timeZone)
        return if (result.days.isEmpty()) DataResult.Empty else DataResult.Success(result)
    }

    // 校验小时平行数组和单位，再按绝对时间保留窗口与缺失位置。
    fun hourly(dto: OpenMeteoForecastResponseDto, location: Location): DataResult<HourlyForecast> {
        if (!validEnvelope(dto, location)) return invalid()
        val hours = dto.hourly ?: return invalid()
        val times = hours.time ?: return invalid()
        val units = dto.hourlyUnits ?: return invalid()
        if (units["time"] != "unixtime") return invalid()
        if (listOfNotNull(hours.temperatures, hours.codes, hours.isDay).any { it.size != times.size }) return invalid()
        if (times.isEmpty()) return DataResult.Empty
        val rows = times.mapIndexedNotNull { index, rawTime ->
            val time = rawTime?.let { runCatching { Instant.ofEpochSecond(it) }.getOrNull() }
                ?: return@mapIndexedNotNull null
            val temperature = hours.temperatures?.get(index)?.takeIf { units["temperature_2m"] == "°C" && it.isFinite() }
            val code = hours.codes?.get(index)?.takeIf { units["weather_code"] == "wmo code" && it >= 0 }
            val isDay = hours.isDay?.get(index)?.takeIf { it in 0..1 }?.let { it == 1 }
            HourlyForecastEntry(time, temperature, code, isDay)
        }
        val result = HourlyForecastWindow(clock).retain(
            HourlyForecast(rows, clock.instant(), false, OpenMeteoAttributions.weather), location.timeZone)
        return if (result.hours.none { it.temperatureCelsius != null || it.weatherCode != null }) DataResult.Empty
        else DataResult.Success(result)
    }

    // 校验响应坐标、错误标记和时区规则，允许供应商返回合法网格坐标。
    private fun validEnvelope(dto: OpenMeteoForecastResponseDto, location: Location): Boolean =
        !dto.error && dto.latitude?.let { it.isFinite() && it in -90.0..90.0 } == true &&
            dto.longitude?.let { it.isFinite() && it in -180.0..180.0 } == true &&
            runCatching { ZoneId.of(dto.timezone).rules == location.timeZone.rules }.getOrDefault(false)

    // 统一返回数据格式错误，使其与合法空数据保持区别。
    private fun invalid() = DataResult.Failure(WeatherFailure.DATA_INVALID)
}
