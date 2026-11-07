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

/**
 * 统一保留天气数据供应商及许可来源，不在页面重复定义。
 */
object OpenMeteoAttributions {
    // 建立所有天气结果共同携带的来源列表。
    val weather = listOf(
        // 保留天气供应商名称及访问地址。
        WeatherAttribution("Open-Meteo", "https://open-meteo.com/"),
        // 保留天气数据的许可名称及说明地址。
        WeatherAttribution("CC BY 4.0", "https://creativecommons.org/licenses/by/4.0/"),
    )
}

/**
 * 在数据边界校验供应商单位、时区与平行数组；单项缺失保留为 null 供界面展示占位。
 */
class OpenMeteoResponseMapper(private val clock: Clock) {
    /*
     * 校验标识、坐标和时区，并拼接不重复的行政路径；无效地点直接丢弃。
     */
    fun mapLocation(dto: OpenMeteoLocationDto): Location? {
        // 缺少身份、名称或明确为错误对象时拒绝该地点。
        if (dto.error || dto.id == null || dto.name.isNullOrBlank()) return null
        // 只接受合法有限纬度，否则丢弃候选。
        val latitude = dto.latitude?.takeIf { it.isFinite() && it in -90.0..90.0 } ?: return null
        // 只接受合法有限经度，否则丢弃候选。
        val longitude =
            dto.longitude?.takeIf { it.isFinite() && it in -180.0..180.0 } ?: return null
        // 时区无法解析时不创建领域地点。
        val zone = runCatching { ZoneId.of(dto.timezone) }.getOrNull() ?: return null
        // 由细到粗拼接行政层级，缺少国家名时使用国家代码。
        val path = listOfNotNull(
            dto.admin4,
            dto.admin3,
            dto.admin2,
            dto.admin1,
            dto.country ?: dto.countryCode
        )
            // 过滤空项、与地名相同的项及重复行政名称。
            .filter { it.isNotBlank() && it != dto.name }.distinct()
        // 构造带供应商身份和有效坐标时区的地点模型。
        return Location(
            OPEN_METEO_LOCATION_PROVIDER,
            dto.id.toString(),
            dto.name,
            path,
            latitude,
            longitude,
            zone
        )
    }

    /*
     * 逐项校验当前天气单位和取值，保留部分有效字段并区分全空与非法响应。
     */
    fun mapCurrentWeather(
        dto: OpenMeteoForecastResponseDto,
        location: Location
    ): DataResult<CurrentWeather> {
        // 先检查公共坐标、时区和错误标记。
        if (!validEnvelope(dto, location)) return invalid()
        // 当前指标对象缺失属于响应结构错误。
        val value = dto.current ?: return invalid()
        // 单位对象缺失时无法可靠解释数值。
        val units = dto.currentUnits ?: return invalid()
        // 要求时间存在且使用约定的 Unix 秒格式。
        if (value.time == null || units["time"] != "unixtime") return invalid()
        // 温度仅接受有限摄氏值，保留负温度与零值。
        val temperature =
            value.temperature?.takeIf { units["temperature_2m"] == "°C" && it.isFinite() }
        // 湿度仅接受有效百分比，不把百分比再次缩放。
        val humidity =
            value.humidity?.takeIf { units["relative_humidity_2m"] == "%" && it.isFinite() && it in 0.0..100.0 }
        // 风速仅接受非负的有限米每秒数值。
        val wind =
            value.wind?.takeIf { units["wind_speed_10m"] == "m/s" && it.isFinite() && it >= 0 }
        // 天气代码要求单位正确且不为负。
        val code = value.code?.takeIf { units["weather_code"] == "wmo code" && it >= 0 }
        // 四项主要指标全无效时返回合法空天气。
        if (temperature == null && humidity == null && wind == null && code == null) return DataResult.Empty
        // 将部分或全部有效指标包装为成功天气。
        return DataResult.Success(
            CurrentWeather(
                // 保留各指标，并将合法昼夜数值转换为布尔值。
                temperature,
                humidity,
                wind,
                code,
                value.isDay?.takeIf { it in 0..1 }?.let { it == 1 },
                // 记录本次获取时刻、来源和主要指标缺失标志。
                clock.instant(),
                OpenMeteoAttributions.weather,
                temperature == null || humidity == null || wind == null || code == null,
            )
        )
    }

    /*
     * 校验日预报平行数组和温度范围，再裁剪为当地七日窗口。
     */
    fun mapDailyForecast(
        dto: OpenMeteoForecastResponseDto,
        location: Location
    ): DataResult<Forecast> {
        // 先保证预报响应与查询地点的公共信息匹配。
        if (!validEnvelope(dto, location)) return invalid()
        // 缺少逐日对象时不能解析预报。
        val days = dto.daily ?: return invalid()
        // 缺少日期轴时无法对应每天指标。
        val dates = days.time ?: return invalid()
        // 缺少日指标单位时报告格式错误。
        val units = dto.dailyUnits ?: return invalid()
        // 逐日日期必须遵守 ISO 日期格式。
        if (units["time"] != "iso8601") return invalid()
        // 任一已提供指标数组长度不匹配日期轴时拒绝整个响应。
        if (listOfNotNull(
                days.maximum,
                days.minimum,
                days.codes
            ).any { it.size != dates.size }
        ) return invalid()
        // 合法空日期数组表示没有日预报。
        if (dates.isEmpty()) return DataResult.Empty
        // 按日期索引转换各行，同时丢弃无效日期或全空行。
        val rows = dates.mapIndexedNotNull { index, rawDate ->
            // 无法解析的日期不进入领域窗口。
            val date = runCatching { LocalDate.parse(rawDate) }.getOrNull()
                ?: return@mapIndexedNotNull null
            // 仅接收当前日期对应的有效最高摄氏温度。
            var maximum = days.maximum?.get(index)
                ?.takeIf { units["temperature_2m_max"] == "°C" && it.isFinite() }
            // 仅接收当前日期对应的有效最低摄氏温度。
            var minimum = days.minimum?.get(index)
                ?.takeIf { units["temperature_2m_min"] == "°C" && it.isFinite() }
            // 高低温倒置时清空两值，避免擅自交换供应商数据。
            if (maximum != null && minimum != null && maximum < minimum) {
                maximum = null; minimum = null
            }
            // 保留有效的日天气代码。
            val code =
                days.codes?.get(index)?.takeIf { units["weather_code"] == "wmo code" && it >= 0 }
            // 该日期所有主要指标缺失时不生成预报行。
            if (maximum == null && minimum == null && code == null) return@mapIndexedNotNull null
            // 以同一索引的数据构造当天预报。
            DailyForecast(date, maximum, minimum, code)
        }
        // 按地点当地七日窗口裁剪，并保留获取时间和原始缺行状态。
        val result = ForecastWindow(clock).retain(
            Forecast(
                rows,
                clock.instant(),
                rows.size != dates.size,
                OpenMeteoAttributions.weather
            ), location.timeZone
        )
        // 窗口无有效日期时返回空，否则返回可展示预报。
        return if (result.days.isEmpty()) DataResult.Empty else DataResult.Success(result)
    }

    /*
     * 校验小时平行数组和单位，再按绝对时间保留窗口与缺失位置。
     */
    fun mapHourlyForecast(
        dto: OpenMeteoForecastResponseDto,
        location: Location
    ): DataResult<HourlyForecast> {
        // 先检查小时响应的公共字段与地点时区。
        if (!validEnvelope(dto, location)) return invalid()
        // 小时指标对象缺失属于数据错误。
        val hours = dto.hourly ?: return invalid()
        // 缺少小时轴时无法对齐温度与天气代码。
        val times = hours.time ?: return invalid()
        // 小时单位对象缺失属于数据错误。
        val units = dto.hourlyUnits ?: return invalid()
        // 小时轴必须使用 Unix 秒以避免当地时间歧义。
        if (units["time"] != "unixtime") return invalid()
        // 所有已存在的指标数组必须与时间轴等长。
        if (listOfNotNull(
                hours.temperatures,
                hours.codes,
                hours.isDay
            ).any { it.size != times.size }
        ) return invalid()
        // 合法空时间轴直接返回无小时数据。
        if (times.isEmpty()) return DataResult.Empty
        // 按同一索引解析每个时刻及其对应指标。
        val rows = times.mapIndexedNotNull { index, rawTime ->
            // 安全将 Unix 秒转换为绝对时刻。
            val time = rawTime?.let { runCatching { Instant.ofEpochSecond(it) }.getOrNull() }
            // 缺失或不可表示的时间不产生记录。
                ?: return@mapIndexedNotNull null
            // 校验该小时温度单位和有限性。
            val temperature = hours.temperatures?.get(index)
                ?.takeIf { units["temperature_2m"] == "°C" && it.isFinite() }
            // 校验该小时天气代码的单位与非负性。
            val code =
                hours.codes?.get(index)?.takeIf { units["weather_code"] == "wmo code" && it >= 0 }
            // 只将零和一解释为有效昼夜状态。
            val isDay = hours.isDay?.get(index)?.takeIf { it in 0..1 }?.let { it == 1 }
            // 构造单个绝对小时的可空指标记录。
            HourlyForecastEntry(time, temperature, code, isDay)
        }
        // 对原始小时序列应用连续二十四小时窗口。
        val result = HourlyForecastWindow(clock).retain(
            // 保留获取时间与归因，并使用地点时区计算窗口。
            HourlyForecast(rows, clock.instant(), false, OpenMeteoAttributions.weather),
            location.timeZone
        )
        // 整个窗口没有温度或天气代码时视为合法空数据。
        return if (result.hours.none { it.temperatureCelsius != null || it.weatherCode != null }) DataResult.Empty
        // 至少存在可展示指标时保留窗口及空位返回成功。
        else DataResult.Success(result)
    }

    /*
     * 校验响应坐标、错误标记和时区规则，允许供应商返回合法网格坐标。
     */
    private fun validEnvelope(dto: OpenMeteoForecastResponseDto, location: Location): Boolean =
        // 拒绝供应商错误标记及缺失或非法纬度。
        !dto.error && dto.latitude?.let { it.isFinite() && it in -90.0..90.0 } == true &&
                // 继续校验响应经度的有限性和取值范围。
                dto.longitude?.let { it.isFinite() && it in -180.0..180.0 } == true &&
                // 比较时区规则而非字符串，并安全处理无效时区。
                runCatching { ZoneId.of(dto.timezone).rules == location.timeZone.rules }.getOrDefault(
                    false
                )

    /*
     * 统一返回数据格式错误，使其与合法空数据保持区别。
     */
    private fun invalid() = DataResult.Failure(WeatherFailure.DATA_INVALID)
}
