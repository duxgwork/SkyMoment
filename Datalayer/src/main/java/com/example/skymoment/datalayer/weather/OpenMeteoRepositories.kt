package com.example.skymoment.datalayer.weather

import com.example.skymoment.domain.Coordinates
import com.example.skymoment.domain.CurrentWeather
import com.example.skymoment.domain.DEVICE_LOCATION_PROVIDER
import com.example.skymoment.domain.DataResult
import com.example.skymoment.domain.Forecast
import com.example.skymoment.domain.HourlyForecast
import com.example.skymoment.domain.LocatedWeather
import com.example.skymoment.domain.Location
import com.example.skymoment.domain.LocationRepository
import com.example.skymoment.domain.WeatherFailure
import com.example.skymoment.domain.WeatherRepository
import com.example.skymoment.domain.map
import com.example.skymoment.datalayer.network.NetworkFailure
import com.example.skymoment.datalayer.network.NetworkResult
import com.example.skymoment.datalayer.network.RequestExecutor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.ZoneId

/** 组合地理编码、中文变体查询和名称更新，对上返回领域地点。 */
class OpenMeteoLocationRepository(
    private val service: OpenMeteoGeocodingService,
    private val executor: RequestExecutor,
    private val mapper: OpenMeteoMapper,
    private val queries: LocationQueries = LocationQueries(),
    private val queryDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val deviceLocationNames: suspend (Location, String) -> Location? = { _, _ -> null },
) : LocationRepository {
    // 在同一总体时限内查询原词和别名，按地点标识合并成功结果，部分失败不抹掉已找到的地点。
    override suspend fun search(query: String, language: String): DataResult<List<Location>> =
        withTimeoutOrNull(45_000) {
            val variants = withContext(queryDispatcher) { queries.variants(query) }
            if (variants.isEmpty()) return@withTimeoutOrNull DataResult.Empty
            val responses = coroutineScope {
                variants.map { term ->
                    async {
                        executor.execute {
                            service.search(
                                term,
                                geoLanguage(language)
                            )
                        }
                    }
                }.awaitAll()
            }
            val results = mutableListOf<Location>()
            var failure: DataResult.Failure? = null
            responses.forEach { response ->
                when (response) {
                    is NetworkResult.Failure -> if (failure == null) failure = response.toDomain()
                    is NetworkResult.Success -> {
                        val body = response.body
                        if (body.error || (body.results == null && body.generationTime == null)) {
                            failure = DataResult.Failure(WeatherFailure.DATA_INVALID)
                        } else {
                            val mapped = body.results.orEmpty().mapNotNull(mapper::location)
                            if (body.results?.isNotEmpty() == true && mapped.isEmpty()) {
                                failure = DataResult.Failure(WeatherFailure.DATA_INVALID)
                            }
                            results += mapped
                        }
                    }
                }
            }
            when {
                results.isNotEmpty() -> DataResult.Success(results.distinctBy { it.id })
                failure != null -> failure!!
                else -> DataResult.Empty
            }
        } ?: DataResult.Failure(WeatherFailure.TIMEOUT)

    // 设备地点使用限时系统译名，其他地点按供应商 ID 刷新；失败不能改变原有坐标和时区。
    override suspend fun refresh(location: Location, language: String): DataResult<Location> =
        if (location.provider == DEVICE_LOCATION_PROVIDER) {
            val resolved = try {
                withTimeoutOrNull(4_000) { deviceLocationNames(location, language) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            val named = resolved?.takeIf { it.id == location.id && it.name.isNotBlank() }
            DataResult.Success(
                if (named == null) location else location.copy(
                    name = named.name,
                    administrativePath = named.administrativePath
                )
            )
        } else when (val result =
            executor.execute { service.get(location.id, geoLanguage(language)) }) {
            is NetworkResult.Failure -> result.toDomain()
            is NetworkResult.Success -> mapper.location(result.body)
                ?.takeIf { it.id == location.id }
                ?.let { DataResult.Success(it) } ?: DataResult.Failure(WeatherFailure.DATA_INVALID)
        }

    // 把有效语言标签归一为供应商支持的中文或英文查询参数。
    private fun geoLanguage(value: String): String = if (value.startsWith("zh")) "zh" else "en"
}

/** 封装各天气端点并执行数据映射，保持区域查询独立。 */
class OpenMeteoWeatherRepository(
    private val service: OpenMeteoForecastService,
    private val executor: RequestExecutor,
    private val mapper: OpenMeteoMapper,
) : WeatherRepository {
    // 请求地点时区内的小时预报，并将传输结果映射为领域结果。
    override suspend fun hourly(location: Location, language: String): DataResult<HourlyForecast> =
        when (val result = executor.execute {
            service.hourly(location.latitude, location.longitude, location.timeZone.id)
        }) {
            is NetworkResult.Success -> mapper.hourly(result.body, location)
            is NetworkResult.Failure -> result.toDomain()
        }

    // 使用设备坐标和 auto 时区请求天气，成功后构造可供首页复用的地点与天气。
    override suspend fun currentAt(
        coordinates: Coordinates,
        language: String
    ): DataResult<LocatedWeather> =
        when (val result = executor.execute {
            service.current(coordinates.latitude, coordinates.longitude, "auto")
        }) {
            is NetworkResult.Failure -> result.toDomain()
            is NetworkResult.Success -> {
                val zone = runCatching { ZoneId.of(result.body.timezone) }.getOrNull()
                if (zone == null) DataResult.Failure(WeatherFailure.DATA_INVALID)
                else {
                    // 使用返回的地点时区，而非设备时区；名称由独立的逆地理编码请求补齐。
                    val location = Location(
                        DEVICE_LOCATION_PROVIDER, "current", "", emptyList(),
                        coordinates.latitude, coordinates.longitude, zone
                    )
                    mapper.current(result.body, location).map { LocatedWeather(location, it) }
                }
            }
        }

    // 使用已选地点的坐标和时区查询当前天气。
    override suspend fun current(location: Location, language: String): DataResult<CurrentWeather> =
        when (val result = executor.execute {
            service.current(location.latitude, location.longitude, location.timeZone.id)
        }) {
            is NetworkResult.Success -> mapper.current(result.body, location)
            is NetworkResult.Failure -> result.toDomain()
        }

    // 请求地点七日预报，保持小时与当前天气请求互相独立。
    override suspend fun forecast(location: Location, language: String): DataResult<Forecast> =
        when (val result = executor.execute {
            service.daily(location.latitude, location.longitude, location.timeZone.id)
        }) {
            is NetworkResult.Success -> mapper.forecast(result.body, location)
            is NetworkResult.Failure -> result.toDomain()
        }
}

// 将通用网络错误映射为天气领域错误，同时保留限流恢复时间。
private fun NetworkResult.Failure.toDomain() = DataResult.Failure(
    when (kind) {
        NetworkFailure.NETWORK -> WeatherFailure.NETWORK
        NetworkFailure.TIMEOUT -> WeatherFailure.TIMEOUT
        NetworkFailure.AUTHENTICATION -> WeatherFailure.AUTHENTICATION
        NetworkFailure.QUOTA -> WeatherFailure.QUOTA
        NetworkFailure.RATE_LIMITED -> WeatherFailure.RATE_LIMITED
        NetworkFailure.SERVER -> WeatherFailure.SERVICE_UNAVAILABLE
        NetworkFailure.INVALID_DATA -> WeatherFailure.DATA_INVALID
        NetworkFailure.REJECTED -> WeatherFailure.REQUEST_REJECTED
    }, retryAt
)
