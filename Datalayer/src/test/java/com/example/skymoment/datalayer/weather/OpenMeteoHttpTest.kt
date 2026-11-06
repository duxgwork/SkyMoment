package com.example.skymoment.datalayer.weather

import com.example.skymoment.domain.Coordinates
import com.example.skymoment.domain.DEVICE_LOCATION_PROVIDER
import com.example.skymoment.domain.DataResult
import com.example.skymoment.domain.Location
import com.example.skymoment.domain.WeatherFailure
import com.example.skymoment.datalayer.network.NetworkFactory
import com.example.skymoment.datalayer.network.RequestExecutor
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import retrofit2.Response
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
/** 覆盖 OpenMeteoHttp 的正常流程、恢复行为与边界条件。 */
class OpenMeteoHttpTest {
    private val server = MockWebServer()
    private val clock = Clock.fixed(Instant.parse("2026-11-05T02:00:00Z"), ZoneOffset.UTC)
    private lateinit var geo: OpenMeteoGeocodingService
    private lateinit var weather: OpenMeteoForecastService
    private val mapper = OpenMeteoMapper(clock)
    @Before
    // 为本用例准备调度器、时钟或测试服务，隔离外部环境。
    fun setup() {
        val cert = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val serverCert = HandshakeCertificates.Builder().heldCertificate(cert).build()
        val clientCert =
            HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build()
        server.useHttps(serverCert.sslSocketFactory(), false); server.start()
        val client = NetworkFactory.client().newBuilder()
            .sslSocketFactory(clientCert.sslSocketFactory(), clientCert.trustManager).build()
        val retrofit = NetworkFactory.retrofit(server.url("/").toString(), client)
        geo = retrofit.create(OpenMeteoGeocodingService::class.java)
        weather = retrofit.create(OpenMeteoForecastService::class.java)
    }

    @After
    // 恢复全局调度器并释放本用例的测试资源。
    fun cleanup() {
        server.shutdown()
    }

    @Test
    // 验证供应商长期配额不重试，其他 429 仍遵守短期冷却。
    fun providerQuotaPolicyDoesNotRetryAndOther429ResponsesKeepTheirCooldown() = runBlocking {
        val repo = OpenMeteoLocationRepository(geo, WeatherRepositoryFactory.requestExecutor(clock), mapper)
        listOf("Daily API request limit exceeded", "Monthly limit", "Quota exceeded").forEach { reason ->
            server.enqueue(MockResponse().setResponseCode(429).setBody("""{"reason":"$reason"}"""))
            assertEquals(DataResult.Failure(WeatherFailure.QUOTA), repo.search("Synthetic", "en"))
        }
        assertEquals(3, server.requestCount)
        listOf("""{"reason":"Minutely limit"}""", "{invalid").forEach { body ->
            val limitedRepo = OpenMeteoLocationRepository(geo, WeatherRepositoryFactory.requestExecutor(clock), mapper)
            server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "120").setBody(body))
            val expected = DataResult.Failure(WeatherFailure.RATE_LIMITED, clock.instant().plusSeconds(120))
            assertEquals(expected, limitedRepo.search("Synthetic", "en"))
            assertEquals(expected, limitedRepo.search("Another", "en"))
        }
        assertEquals(5, server.requestCount)
    }

    @Test
    // 验证坐标启动使用 auto 时区并拒绝缺失的当前天气。
    fun coordinateStartupUsesAutoTimezoneAndRejectsMissingWeather() = runBlocking {
        val repo = OpenMeteoWeatherRepository(weather, RequestExecutor(clock), mapper)
        val coordinates = Coordinates(30.12345, 120.98765)
        server.enqueue(MockResponse().setBody("""{"latitude":30.1,"longitude":121,"timezone":"Asia/Shanghai","current_units":{"time":"unixtime","temperature_2m":"°C"},"current":{"time":1793844000,"temperature_2m":12}}"""))
        val result = (repo.currentAt(coordinates, "zh") as DataResult.Success).data
        assertEquals(ZoneId.of("Asia/Shanghai"), result.location.timeZone)
        assertEquals(DEVICE_LOCATION_PROVIDER, result.location.provider)
        assertEquals(12.0, result.current.temperatureCelsius)
        val request = server.takeRequest()
        assertEquals("auto", request.requestUrl!!.queryParameter("timezone"))
        assertEquals("30.12345", request.requestUrl!!.queryParameter("latitude"))
        assertEquals("120.98765", request.requestUrl!!.queryParameter("longitude"))
        server.enqueue(MockResponse().setBody("""{"latitude":30.1,"longitude":121,"timezone":"invalid"}"""))
        assertEquals(
            DataResult.Failure(WeatherFailure.DATA_INVALID),
            repo.currentAt(coordinates, "en")
        )
        server.enqueue(MockResponse().setBody("""{"latitude":30.1,"longitude":121,"timezone":"Asia/Shanghai","current_units":{"time":"unixtime"},"current":{"time":1793844000}}"""))
        assertEquals(DataResult.Empty, repo.currentAt(coordinates, "en"))
    }

    @Test
    // 验证真实序列化区分合法空响应、损坏结构并容忍未知字段。
    fun realSerializationDistinguishesEmptyBrokenEnvelopeAndUnknownFields() = runBlocking {
        val repo = OpenMeteoLocationRepository(geo, RequestExecutor(clock), mapper)
        server.enqueue(MockResponse().setBody("""{"generationtime_ms":1,"extra":true}"""))
        assertEquals(DataResult.Empty, repo.search("Nowhere", "fr"))
        val request = server.takeRequest()
        assertEquals("/v1/search", request.requestUrl!!.encodedPath)
        assertEquals("en", request.requestUrl!!.queryParameter("language"))
        assertNull(request.getHeader("Authorization"))
        server.enqueue(MockResponse().setBody("""{"error":true,"reason":"raw private error"}"""))
        assertEquals(DataResult.Failure(WeatherFailure.DATA_INVALID), repo.search("Nowhere", "en"))
        server.enqueue(MockResponse().setBody("""{"results":[{"id":1,"name":"合成区","latitude":30.12345,"longitude":120.98765,"timezone":"Asia/Shanghai","admin1":"合成省","country":"中国","extra":42}]}"""))
        val place = (repo.search("Synthetic", "zh-Hant") as DataResult.Success).data.single()
        server.takeRequest()
        assertEquals("zh", server.takeRequest().requestUrl!!.queryParameter("language"))
        assertEquals(listOf("合成省", "中国"), place.administrativePath)
        server.enqueue(MockResponse().setBody("""{"id":1,"name":"Synthetic","latitude":30.12345,"longitude":120.98765,"timezone":"Asia/Shanghai"}"""))
        assertEquals("Synthetic", (repo.refresh(place, "en") as DataResult.Success).data.name)
        assertEquals("1", server.takeRequest().requestUrl!!.queryParameter("id"))
    }

    @Test
    // 验证天气请求精确使用命名坐标参数，不附加语言或认证。
    fun forecastRequestsUseExactNamedCoordinatesAndNoLanguageOrAuth() = runBlocking {
        val repo = OpenMeteoWeatherRepository(weather, RequestExecutor(clock), mapper)
        val place = Location(
            "Open-Meteo",
            "1",
            "Synthetic",
            emptyList(),
            30.12345,
            120.98765,
            ZoneId.of("Asia/Shanghai")
        )
        server.enqueue(MockResponse().setBody("""{"latitude":30.1,"longitude":121,"timezone":"Asia/Shanghai","current_units":{"time":"unixtime","temperature_2m":"°C","relative_humidity_2m":"%","wind_speed_10m":"m/s","weather_code":"wmo code","is_day":""},"current":{"time":1793844000,"temperature_2m":0,"relative_humidity_2m":65,"wind_speed_10m":0,"weather_code":3,"is_day":1}}"""))
        assertEquals(65.0, (repo.current(place, "zh") as DataResult.Success).data.humidityPercent)
        val current = server.takeRequest()
        assertEquals("30.12345", current.requestUrl!!.queryParameter("latitude"))
        assertEquals("120.98765", current.requestUrl!!.queryParameter("longitude"))
        assertEquals("ms", current.requestUrl!!.queryParameter("wind_speed_unit"))
        assertEquals("unixtime", current.requestUrl!!.queryParameter("timeformat"))
        assertNull(current.requestUrl!!.queryParameter("language"))
        assertNull(current.requestUrl!!.queryParameter("apikey")); assertNull(current.getHeader("Authorization"))
        server.enqueue(MockResponse().setBody("""{"latitude":30.1,"longitude":121,"timezone":"Asia/Shanghai","daily_units":{"time":"iso8601"},"daily":{"time":[]}}"""))
        assertEquals(DataResult.Empty, repo.forecast(place, "en"))
        val daily = server.takeRequest()
        assertEquals("7", daily.requestUrl!!.queryParameter("forecast_days"))
        assertNull(daily.requestUrl!!.queryParameter("current"))
        assertEquals(
            "weather_code,temperature_2m_max,temperature_2m_min",
            daily.requestUrl!!.queryParameter("daily")
        )
    }

    @Test
    // 验证小时请求使用二十四小时 Unix 时间并解析温度、天气与昼夜。
    fun hourlyUses24HoursUnixTimeAndDecodesTemperatureConditionsAndNight() = runBlocking {
        val repo = OpenMeteoWeatherRepository(weather, RequestExecutor(clock), mapper)
        val place = Location(
            "test",
            "1",
            "Synthetic",
            emptyList(),
            30.12345,
            120.98765,
            ZoneId.of("Asia/Shanghai")
        )
        val times = (0L..23).joinToString(",") {
            clock.instant().plusSeconds(it * 3600).epochSecond.toString()
        }
        server.enqueue(
            MockResponse().setBody(
                """{"latitude":30.1,"longitude":121,"timezone":"Asia/Shanghai",
            "hourly_units":{"time":"unixtime","temperature_2m":"°C","weather_code":"wmo code","is_day":""},
            "hourly":{"time":[$times],"temperature_2m":[${List(24) { "-2.5" }.joinToString(",")}],
            "weather_code":[${List(24) { "0" }.joinToString(",")}],"is_day":[${
            List(24) { "0" }.joinToString(
                ","
            )
        }]}}"""
        )
        )
        val result = (repo.hourly(place, "zh") as DataResult.Success).data
        assertEquals(24, result.hours.size)
        assertEquals(-2.5, result.hours.first().temperatureCelsius)
        assertEquals(false, result.hours.first().isDay)
        assertFalse(result.incomplete)
        val url = server.takeRequest().requestUrl!!
        assertEquals("30.12345", url.queryParameter("latitude"))
        assertEquals("120.98765", url.queryParameter("longitude"))
        assertEquals("Asia/Shanghai", url.queryParameter("timezone"))
        assertEquals("24", url.queryParameter("forecast_hours"))
        assertEquals("temperature_2m,weather_code,is_day", url.queryParameter("hourly"))
        assertEquals("celsius", url.queryParameter("temperature_unit"))
        assertEquals("unixtime", url.queryParameter("timeformat"))
    }

    @Test
    // 验证所有别名搜索共享总体时间预算并传播取消。
    fun aliasSearchHasOneOverallBudgetAndCancellationPropagates() = runTest {
        var started = 0
        var cancelled = 0
        val slow = object : OpenMeteoGeocodingService {
            // 提供可控的搜索响应，隔离真实网络与系统环境。
            override suspend fun search(
                query: String,
                language: String,
                count: Int,
                format: String
            ): Response<OpenMeteoGeocodingResponseDto> {
                started++
                try {
                    awaitCancellation()
                } finally {
                    cancelled++
                }
            }

            // 提供可控的接口响应，隔离真实网络与系统环境。
            override suspend fun get(id: String, language: String): Response<OpenMeteoLocationDto> =
                error("unused")
        }
        val repo = OpenMeteoLocationRepository(
            slow, RequestExecutor(clock), mapper,
            LocationQueries { "haidian" }, StandardTestDispatcher(testScheduler)
        )
        val before = testScheduler.currentTime
        assertEquals(DataResult.Failure(WeatherFailure.TIMEOUT), repo.search("海淀区", "zh"))
        assertEquals(45_000, testScheduler.currentTime - before)
        assertEquals(3, started); assertEquals(3, cancelled)
        val job = launch { repo.search("海淀区", "zh") }
        runCurrent(); job.cancelAndJoin()
        assertEquals(6, started); assertEquals(6, cancelled)
    }
}
