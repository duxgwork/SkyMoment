package com.example.skymoment.datalayer.weather

import com.example.skymoment.domain.DEVICE_LOCATION_PROVIDER
import com.example.skymoment.domain.DataResult
import com.example.skymoment.domain.Location
import com.example.skymoment.domain.WeatherFailure
import com.example.skymoment.datalayer.network.RequestExecutor
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** 覆盖 OpenMeteoRepository 的正常流程、恢复行为与边界条件。 */
class OpenMeteoRepositoryTest {
    private val clock = Clock.fixed(Instant.parse("2026-11-05T02:00:00Z"), ZoneOffset.UTC)
    private val mapper = OpenMeteoMapper(clock)
    private val executor = RequestExecutor(clock, retryDelayMillis = { 0 })

    @Test
    // 验证设备地点译名失败、空结果和超时均保留原地点。
    fun deviceNameFailureEmptyAndTimeoutPreserveTheExistingLocation() = runTest {
        val service = object : OpenMeteoGeocodingService {
            // 提供可控的搜索响应，隔离真实网络与系统环境。
            override suspend fun search(
                query: String,
                language: String,
                count: Int,
                format: String
            ): Response<OpenMeteoGeocodingResponseDto> = error("unused")

            // 提供可控的接口响应，隔离真实网络与系统环境。
            override suspend fun get(id: String, language: String): Response<OpenMeteoLocationDto> =
                error("Device coordinates must not be queried as a GeoNames ID")
        }
        val place = mapper.location(location(1))!!
            .copy(provider = DEVICE_LOCATION_PROVIDER, name = "现有地点")
        var resolve: suspend (Location, String) -> Location? = { _, _ -> null }
        val repo = OpenMeteoLocationRepository(
            service,
            executor,
            mapper,
            deviceLocationNames = { location, language -> resolve(location, language) })
        assertEquals(DataResult.Success(place), repo.refresh(place, "zh"))
        resolve = { _, _ -> throw IOException("unavailable") }
        assertEquals(DataResult.Success(place), repo.refresh(place, "zh"))
        resolve = { _, _ -> awaitCancellation() }
        val start = testScheduler.currentTime
        assertEquals(DataResult.Success(place), repo.refresh(place, "zh"))
        assertEquals(4_000, testScheduler.currentTime - start)
        resolve = { location, language ->
            assertEquals("zh-Hans", language)
            location.copy(name = "浦东新区", administrativePath = listOf("上海市", "中国"))
        }
        val named = (repo.refresh(place, "zh-Hans") as DataResult.Success).data
        assertEquals("浦东新区", named.name)
        assertEquals(place.latitude, named.latitude, 0.0)
        assertEquals(place.timeZone, named.timeZone)
    }

    @Test
    // 验证拼音结果按地点 ID 合并，失败变体不抹掉成功结果。
    fun pinyinIsMergedByIdAndAFailedVariantDoesNotEraseActualResults() = runBlocking {
        val requested = mutableListOf<Pair<String, String>>()
        var failPinyin = false
        val service = object : OpenMeteoGeocodingService {
            // 提供可控的搜索响应，隔离真实网络与系统环境。
            override suspend fun search(
                query: String,
                language: String,
                count: Int,
                format: String
            ): Response<OpenMeteoGeocodingResponseDto> {
                requested += query to language
                if (failPinyin && query == "kunshan") return Response.error(
                    503,
                    "{}".toResponseBody()
                )
                val rows = if (query == "kunshan") listOf(
                    location(2),
                    location(1)
                ) else listOf(location(1))
                return Response.success(OpenMeteoGeocodingResponseDto(rows, 1.0))
            }

            // 提供可控的接口响应，隔离真实网络与系统环境。
            override suspend fun get(id: String, language: String) =
                Response.success(location(id.toLong()))
        }
        val repo =
            OpenMeteoLocationRepository(service, executor, mapper, LocationQueries { "kunshan" })
        val result = repo.search("昆山", "zh")
        assertEquals(listOf("昆山" to "zh", "kunshan" to "zh"), requested)
        assertEquals(listOf("1", "2"), (result as DataResult.Success).data.map { it.id })
        val selected = result.data.last()
        assertEquals(selected, (repo.refresh(selected, "en") as DataResult.Success).data)
        failPinyin = true
        assertEquals(
            listOf("1"),
            (repo.search("昆山", "zh") as DataResult.Success).data.map { it.id })
        val before = requested.size
        assertEquals(DataResult.Empty, repo.search("昆", "zh"))
        assertEquals(before, requested.size)
    }

    @Test
    // 验证合法空响应与供应商错误响应保持区别。
    fun legalEmptyAndErrorEnvelopeAreDifferent() = runBlocking {
        var body = OpenMeteoGeocodingResponseDto(generationTime = 1.0)
        val service = object : OpenMeteoGeocodingService {
            // 提供可控的搜索响应，隔离真实网络与系统环境。
            override suspend fun search(
                query: String,
                language: String,
                count: Int,
                format: String
            ) = Response.success(body)

            // 提供可控的接口响应，隔离真实网络与系统环境。
            override suspend fun get(id: String, language: String) =
                Response.success(location(1).copy(error = true))
        }
        val repo = OpenMeteoLocationRepository(service, executor, mapper)
        assertEquals(DataResult.Empty, repo.search("Missing", "en"))
        body = OpenMeteoGeocodingResponseDto(error = true)
        assertEquals(DataResult.Failure(WeatherFailure.DATA_INVALID), repo.search("Missing", "en"))
        body = OpenMeteoGeocodingResponseDto()
        assertEquals(DataResult.Failure(WeatherFailure.DATA_INVALID), repo.search("Missing", "en"))
    }

    @Test
    // 验证当前和逐日请求使用正确坐标、单位以及独立字段集。
    fun currentAndDailyUseNamedCoordinatesMetricUnitsAndDistinctFields() = runBlocking {
        val place = mapper.location(location(1))!!
        val calls = mutableListOf<String>()
        val service = object : OpenMeteoForecastService {
            // 提供可控的小时预报样本，隔离真实网络与系统环境。
            override suspend fun hourly(
                latitude: Double, longitude: Double, timezone: String, variables: String,
                hours: Int, temperatureUnit: String, timeFormat: String
            ): Response<OpenMeteoForecastResponseDto> = error("unused")

            // 提供可控的当前天气样本，隔离真实网络与系统环境。
            override suspend fun current(
                latitude: Double, longitude: Double, timezone: String, variables: String,
                temperatureUnit: String, windUnit: String, timeFormat: String
            ): Response<OpenMeteoForecastResponseDto> {
                assertEquals(place.latitude, latitude, 0.0); assertEquals(
                    place.longitude,
                    longitude,
                    0.0
                )
                assertEquals("Asia/Shanghai", timezone); assertEquals("celsius", temperatureUnit)
                assertEquals("ms", windUnit); assertEquals("unixtime", timeFormat)
                assertTrue(variables.contains("relative_humidity_2m"))
                calls += "current"
                return Response.success(OpenMeteoForecastResponseDto(error = true))
            }

            // 提供可控的逐日响应样本，隔离真实网络与系统环境。
            override suspend fun daily(
                latitude: Double, longitude: Double, timezone: String, variables: String,
                days: Int, temperatureUnit: String, windUnit: String, timeFormat: String
            ): Response<OpenMeteoForecastResponseDto> {
                assertEquals(7, days); assertEquals("iso8601", timeFormat)
                assertEquals("weather_code,temperature_2m_max,temperature_2m_min", variables)
                calls += "daily"
                return Response.success(
                    OpenMeteoForecastResponseDto(
                        latitude, longitude, timezone,
                        dailyUnits = mapOf("time" to "iso8601"), daily = OpenMeteoDailyForecastDto(emptyList())
                    )
                )
            }
        }
        val repo = OpenMeteoWeatherRepository(service, executor, mapper)
        assertEquals(DataResult.Failure(WeatherFailure.DATA_INVALID), repo.current(place, "en"))
        assertEquals(DataResult.Empty, repo.forecast(place, "en"))
        assertEquals(listOf("current", "daily"), calls)
    }

    // 提供可控的地点样本，隔离真实网络与系统环境。
    private fun location(id: Long) = OpenMeteoLocationDto(
        id, "合成区$id", 30.12345, 120.98765,
        "Asia/Shanghai", "中国", "CN", "合成省", "合成市"
    )
}
