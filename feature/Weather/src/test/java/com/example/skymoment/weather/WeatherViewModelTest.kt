package com.example.skymoment.weather

import com.example.skymoment.domain.CurrentWeather
import com.example.skymoment.domain.DEVICE_LOCATION_PROVIDER
import com.example.skymoment.domain.DailyForecast
import com.example.skymoment.domain.DataResult
import com.example.skymoment.domain.Forecast
import com.example.skymoment.domain.HourlyForecast
import com.example.skymoment.domain.HourlyForecastEntry
import com.example.skymoment.domain.HourlyForecastWindow
import com.example.skymoment.domain.Location
import com.example.skymoment.domain.LocationNameRepository
import com.example.skymoment.domain.LocationSessionRepository
import com.example.skymoment.domain.WeatherFailure
import com.example.skymoment.domain.SelectedLocationWeatherRepository
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
/** 覆盖 WeatherViewModel 的正常流程、恢复行为与边界条件。 */
class WeatherViewModelTest {
    @get:org.junit.Rule
    val mainDispatcher = com.example.skymoment.test.MainDispatcherRule()
    private val dispatcher get() = mainDispatcher.dispatcher
    private val zone = ZoneId.of("America/New_York")
    private val place = Location("test", "1", "示例区", emptyList(), 1.0, 2.0, zone)
    private val sessions = object : LocationSessionRepository {
        // 提供可控的会话选择结果，隔离真实网络与系统环境。
        override fun createSession(location: Location, initialWeather: CurrentWeather?) = "token"
        // 提供可控的会话解析结果，隔离真实网络与系统环境。
        override fun findLocation(token: String) = place.takeIf { token == "token" }
        // 提供可控的数据清理行为，隔离真实网络与系统环境。
        override fun clear() = Unit
    }


    // 提供随测试调度器推进的时钟，使时间边界测试可重复。
    private fun clock(start: String = "2026-11-05T17:00:00Z"): Clock =
        com.example.skymoment.test.SchedulerClock(dispatcher.scheduler, Instant.parse(start))

    // 创建固定天气样本，避免依赖实时公共接口。
    private fun sample(clock: Clock) =
        CurrentWeather(0.0, 65.0, 0.0, 0, true, clock.instant(), emptyList())

    // 提供可控的七日预报样本，隔离真实网络与系统环境。
    private fun forecast(clock: Clock) = Forecast((0L..6L).map {
        DailyForecast(LocalDate.now(clock.withZone(zone)).plusDays(it), 5.0, -2.0, 3)
    }, clock.instant(), false, emptyList())

    /** 可独立控制三个天气区域的响应与调用次数。 */
    private class FakeWeather(
        var now: suspend () -> DataResult<CurrentWeather>,
        var days: suspend () -> DataResult<Forecast>,
        var hours: suspend () -> DataResult<HourlyForecast> = { DataResult.Empty },
    ) : SelectedLocationWeatherRepository {
        var currentCalls = 0;
        var forecastCalls = 0;
        var hourlyCalls = 0
        // 提供可控的当前天气样本，隔离真实网络与系统环境。
        override suspend fun getCurrentWeather(
            location: Location,
            language: String
        ): DataResult<CurrentWeather> {
            currentCalls++; return now()
        }

        // 提供可控的七日预报样本，隔离真实网络与系统环境。
        override suspend fun getDailyForecast(location: Location, language: String): DataResult<Forecast> {
            forecastCalls++; return days()
        }

        // 提供可控的小时预报样本，隔离真实网络与系统环境。
        override suspend fun getHourlyForecast(
            location: Location,
            language: String
        ): DataResult<HourlyForecast> {
            hourlyCalls++; return hours()
        }
    }

    // 提供可控的小时预报样本，隔离真实网络与系统环境。
    private fun hourly(clock: Clock): HourlyForecast {
        val start = HourlyForecastWindow(clock).currentHour(zone)
        return HourlyForecast(
            (0L..23).map {
                HourlyForecastEntry(
                    start.plusSeconds(it * 3600),
                    it.toDouble(),
                    3,
                    true
                )
            },
            clock.instant(), false, emptyList()
        )
    }

    @Test
    // 验证整点滚动小时窗口，独立重试时保留仍有效的小时数据。
    fun hourlyRollsAtHourBoundaryAndRetriesIndependentlyWhileKeepingAvailableHours() =
        runTest(dispatcher) {
            val clock = clock("2026-11-05T17:59:59Z")
            val repo = FakeWeather(
                { DataResult.Success(sample(clock)) },
                { DataResult.Success(forecast(clock)) },
                { DataResult.Success(hourly(clock)) })
            val vm = WeatherViewModel(repo, sessions, "token", clock = clock)
            vm.onForeground("en"); runCurrent()
            val firstHour = vm.state.value.hourly.data!!.hours.first().time
            assertEquals(24, vm.state.value.hourly.data!!.hours.size)
            vm.onBackground(); vm.onForeground("en"); runCurrent()
            assertEquals(1, repo.hourlyCalls)
            repo.hours = { DataResult.Failure(WeatherFailure.NETWORK) }
            advanceTimeBy(1001); runCurrent()
            assertEquals(2, repo.hourlyCalls)
            assertEquals(
                firstHour.plusSeconds(3600),
                vm.state.value.hourly.data!!.hours.first().time
            )
            assertEquals(1.0, vm.state.value.hourly.data!!.hours.first().temperatureCelsius)
            assertNull(vm.state.value.hourly.data!!.hours.last().temperatureCelsius)
            assertEquals(WeatherFailure.NETWORK, vm.state.value.hourly.error?.reason)
            assertEquals(1, repo.currentCalls); assertEquals(1, repo.forecastCalls)
            repo.hours = { DataResult.Success(hourly(clock)) }
            vm.retryHourly(); runCurrent()
            assertNull(vm.state.value.hourly.error)
            assertFalse(vm.state.value.hourly.data!!.incomplete)
            vm.onBackground(); advanceTimeBy(3_600_000); runCurrent()
            assertEquals(3, repo.hourlyCalls)
            vm.onForeground("en"); runCurrent()
            assertEquals(4, repo.hourlyCalls)
            assertEquals(
                HourlyForecastWindow(clock).currentHour(zone),
                vm.state.value.hourly.data!!.hours.first().time
            )
            vm.onBackground()
        }

    @Test
    // 验证小时失败不隐藏逐日内容，冷却同时约束各区域。
    fun hourlyFailureDoesNotHideDailyForecastAndCooldownAppliesToAllRequests() =
        runTest(dispatcher) {
            val clock = clock()
            val repo = FakeWeather(
                { DataResult.Success(sample(clock)) },
                { DataResult.Success(forecast(clock)) },
                { DataResult.Failure(WeatherFailure.RATE_LIMITED, clock.instant().plusSeconds(3)) })
            val vm = WeatherViewModel(repo, sessions, "token", clock = clock)
            vm.onForeground("en"); runCurrent()
            assertNotNull(vm.state.value.current.data); assertNotNull(vm.state.value.forecast.data)
            assertEquals(3L, vm.state.value.cooldownSeconds)
            vm.refresh(); vm.retryHourly(); runCurrent()
            assertEquals(1, repo.hourlyCalls); assertEquals(1, repo.currentCalls); assertEquals(
            1,
            repo.forecastCalls
        )
            advanceTimeBy(3000); runCurrent()
            repo.hours = { DataResult.Success(hourly(clock)) }
            vm.retryHourly(); runCurrent()
            assertEquals(24, vm.state.value.hourly.data!!.hours.size)
            assertNull(vm.state.value.hourly.error)
            vm.onBackground()
        }

    @Test
    // 验证各区域独立合并重复点击，无效会话不发请求。
    fun independentRegionsMergeClicksAndInvalidTokenDoesNotRequest() = runTest(dispatcher) {
        val clock = clock()
        val waiting = CompletableDeferred<DataResult<CurrentWeather>>()
        val repo = FakeWeather({ waiting.await() }, { DataResult.Failure(WeatherFailure.NETWORK) })
        val vm = WeatherViewModel(repo, sessions, "token", clock = clock)
        vm.onForeground("zh"); vm.refresh(); runCurrent()
        assertTrue(vm.state.value.current.loading)
        assertEquals(WeatherFailure.NETWORK, vm.state.value.forecast.error?.reason)
        assertEquals(1, repo.currentCalls); assertEquals(1, repo.forecastCalls)
        waiting.complete(DataResult.Success(sample(clock))); runCurrent()
        assertEquals(0.0, vm.state.value.current.data?.temperatureCelsius)
        repo.days = { DataResult.Success(forecast(clock)) }
        vm.retryForecast(); runCurrent()
        assertEquals(7, vm.state.value.forecast.data?.days?.size)
        repo.now = { DataResult.Failure(WeatherFailure.TIMEOUT) }
        val original = vm.state.value.current.data
        vm.refresh(); runCurrent()
        assertEquals(original, vm.state.value.current.data)
        assertEquals(WeatherFailure.TIMEOUT, vm.state.value.current.error?.reason)
        assertNotNull(vm.state.value.forecast.data)
        vm.onBackground()
        val invalid = WeatherViewModel(repo, sessions, "expired", clock = clock)
        invalid.onForeground("en"); runCurrent()
        assertNull(invalid.state.value.location)
        assertEquals(2, repo.currentCalls)
    }

    @Test
    // 验证启动天气直接展示，天气页不重复查询当前天气。
    fun startupWeatherIsRenderedWithoutRepeatingTheCurrentRequest() = runTest(dispatcher) {
        val clock = clock()
        val current = sample(clock)
        val seededSessions = object : LocationSessionRepository by sessions {
            // 按有效会话读取启动天气，供天气页复用并减少重复请求。
            override fun findInitialWeather(token: String) = current.takeIf { token == "token" }
        }
        val repo = FakeWeather({ DataResult.Failure(WeatherFailure.NETWORK) }, { DataResult.Empty })
        val vm = WeatherViewModel(repo, seededSessions, "token", clock = clock)
        assertEquals(current, vm.state.value.current.data)
        vm.onForeground("en"); runCurrent()
        assertEquals(0, repo.currentCalls); assertEquals(1, repo.forecastCalls)
        assertEquals(current, vm.state.value.current.data)
        vm.onBackground()
    }

    @Test
    // 验证译名晚于天气完成也可更新，失败保留已有名称。
    fun deviceNameResolvesAfterWeatherAndFailurePreservesTheDisplayedName() = runTest(dispatcher) {
        val clock = clock()
        val current = sample(clock)
        val devicePlace = place.copy(provider = DEVICE_LOCATION_PROVIDER, name = "")
        val initialName = CompletableDeferred<DataResult<Location>>()
        var calls = 0
        val locations = object : LocationNameRepository {
            // 提供可控的地点译名结果，隔离真实网络与系统环境。
            override suspend fun refreshLocationName(
                location: Location,
                language: String
            ): DataResult<Location> {
                calls++
                return if (calls == 1) initialName.await() else DataResult.Failure(WeatherFailure.NETWORK)
            }
        }
        val seeded = object : LocationSessionRepository by sessions {
            // 提供可控的会话解析结果，隔离真实网络与系统环境。
            override fun findLocation(token: String) = devicePlace
            // 按有效会话读取启动天气，供天气页复用并减少重复请求。
            override fun findInitialWeather(token: String) = current
        }
        val repo = FakeWeather({ DataResult.Success(current) }, { DataResult.Empty })
        val vm = WeatherViewModel(repo, seeded, "token", locations, clock)
        vm.onForeground("en"); runCurrent()
        assertEquals(current, vm.state.value.current.data)
        assertEquals("", vm.state.value.location?.name)
        assertEquals(0, repo.currentCalls)
        initialName.complete(DataResult.Success(devicePlace.copy(name = "Resolved district"))); runCurrent()
        assertEquals("Resolved district", vm.state.value.location?.name)
        vm.onBackground(); vm.onForeground("en"); runCurrent()
        assertEquals(1, calls)
        vm.onForeground("zh"); runCurrent()
        assertEquals("Resolved district", vm.state.value.location?.name)
        assertEquals(current, vm.state.value.current.data)
        assertFalse(vm.state.value.nameError)
        vm.onBackground()
    }

    @Test
    // 验证空与失败互相独立，成功重试只清除对应区域错误。
    fun emptyAndFailureAreIndependentAndSuccessfulRetryClearsOnlyItsError() = runTest(dispatcher) {
        val clock = clock()
        val repo = FakeWeather(
            { DataResult.Failure(WeatherFailure.NETWORK) },
            { DataResult.Success(forecast(clock)) })
        val vm = WeatherViewModel(repo, sessions, "token", clock = clock)
        vm.onForeground("en"); runCurrent()
        assertNotNull(vm.state.value.forecast.data)
        repo.now = { DataResult.Empty }; vm.retryCurrent(); runCurrent()
        assertTrue(vm.state.value.current.empty); assertNull(vm.state.value.current.error)
        repo.now = { DataResult.Success(sample(clock)) }; vm.retryCurrent(); runCurrent()
        assertFalse(vm.state.value.current.empty); assertNotNull(vm.state.value.current.data)
        vm.onBackground()
    }

    @Test
    // 验证共享冷却阻止请求，到期后允许重试。
    fun sharedCooldownPreventsRequestsAndThenAllowsRetry() = runTest(dispatcher) {
        val clock = clock()
        val repo = FakeWeather({
            DataResult.Failure(
                WeatherFailure.RATE_LIMITED,
                clock.instant().plusSeconds(3)
            )
        }, { DataResult.Empty })
        val vm = WeatherViewModel(repo, sessions, "token", clock = clock)
        vm.onForeground("en"); runCurrent()
        vm.refresh(); runCurrent(); assertEquals(1, repo.currentCalls)
        assertEquals(3L, vm.state.value.cooldownSeconds)
        advanceTimeBy(3000); runCurrent()
        assertEquals(0L, vm.state.value.cooldownSeconds)
        repo.now = { DataResult.Success(sample(clock)) }
        vm.retryCurrent(); runCurrent(); assertEquals(2, repo.currentCalls)
        assertNull(vm.state.value.current.error)
        vm.onBackground()
    }

    @Test
    // 验证午夜刷新失败仍裁剪昨天，旋转保留新鲜数据。
    fun midnightPrunesYesterdayEvenWhenRefreshFailsAndRotationKeepsFreshData() =
        runTest(dispatcher) {
            val clock = clock("2026-11-06T04:59:59Z")
            val repo = FakeWeather(
                { DataResult.Success(sample(clock)) },
                { DataResult.Success(forecast(clock)) })
            val vm = WeatherViewModel(repo, sessions, "token", clock = clock)
            vm.onForeground("en"); runCurrent()
            val yesterday = vm.state.value.today!!
            vm.onBackground(); vm.onForeground("en"); runCurrent()
            assertEquals(1, repo.currentCalls); assertEquals(1, repo.forecastCalls)
            repo.days = { DataResult.Failure(WeatherFailure.NETWORK) }
            advanceTimeBy(1001); runCurrent()
            assertEquals(yesterday.plusDays(1), vm.state.value.today)
            assertEquals(6, vm.state.value.forecast.data?.days?.size)
            assertFalse(vm.state.value.forecast.data!!.days.any { it.date == yesterday })
            assertTrue(vm.state.value.forecast.data!!.incomplete)
            assertEquals(2, repo.forecastCalls)
            vm.onBackground()
            advanceTimeBy(600_000); runCurrent(); assertEquals(1, repo.currentCalls)
            vm.onForeground("en"); runCurrent(); assertEquals(2, repo.currentCalls)
            vm.onBackground()
        }

    @Test
    // 验证旧语言的迟到天气不能覆盖新代次。
    fun lateWeatherFromOldLanguageCannotOverwriteTheNewGeneration() = runTest(dispatcher) {
        val clock = clock()
        val old = CompletableDeferred<DataResult<CurrentWeather>>()
        var first = true
        val repo = FakeWeather({
            if (first) {
                first = false; withContext(NonCancellable) { old.await() }
            } else DataResult.Success(sample(clock).copy(temperatureCelsius = 12.0))
        }, { DataResult.Empty })
        val vm = WeatherViewModel(repo, sessions, "token", clock = clock)
        vm.onForeground("zh"); runCurrent()
        vm.onForeground("en"); runCurrent()
        old.complete(DataResult.Success(sample(clock).copy(temperatureCelsius = -50.0))); runCurrent()
        assertEquals(12.0, vm.state.value.current.data?.temperatureCelsius)
        vm.onBackground()
    }

    @Test
    // 验证迟到预报不能覆盖新语言或其他区域的状态。
    fun lateForecastsCannotOverwriteNewLanguageOrOtherRegions() = runTest(dispatcher) {
        val clock = clock()
        val oldDays = CompletableDeferred<DataResult<Forecast>>()
        val oldHours = CompletableDeferred<DataResult<HourlyForecast>>()
        val newDays = CompletableDeferred<DataResult<Forecast>>()
        val newHours = CompletableDeferred<DataResult<HourlyForecast>>()
        val repo = FakeWeather(
            { DataResult.Success(sample(clock)) },
            { withContext(NonCancellable) { oldDays.await() } },
            { withContext(NonCancellable) { oldHours.await() } },
        )
        val vm = WeatherViewModel(repo, sessions, "token", clock = clock)
        vm.onForeground("zh"); runCurrent()
        repo.days = { newDays.await() }
        repo.hours = { newHours.await() }
        vm.onForeground("en"); runCurrent()

        // 小时预报先完成，七日预报后完成；两者都必须保留其他区域的最新内容。
        val expectedHours = hourly(clock)
        val expectedDays = forecast(clock)
        newHours.complete(DataResult.Success(expectedHours)); runCurrent()
        assertTrue(vm.state.value.forecast.loading)
        newDays.complete(DataResult.Success(expectedDays)); runCurrent()
        oldDays.complete(DataResult.Empty)
        oldHours.complete(DataResult.Failure(WeatherFailure.NETWORK)); runCurrent()

        assertEquals(expectedDays, vm.state.value.forecast.data)
        assertEquals(expectedHours, vm.state.value.hourly.data)
        assertNotNull(vm.state.value.current.data)
        assertNull(vm.state.value.hourly.error)
        assertFalse(vm.state.value.forecast.empty)
        vm.onBackground()
    }

    @Test
    // 验证语言代次阻止迟到译名，离线保留数值并可恢复。
    fun languageGenerationRejectsLateNameAndPreservesNumbersOfflineThenRecovers() =
        runTest(dispatcher) {
            val clock = clock()
            val oldName = CompletableDeferred<DataResult<Location>>()
            var offline = false
            val locations = object : LocationNameRepository {
                // 提供可控的地点译名结果，隔离真实网络与系统环境。
                override suspend fun refreshLocationName(
                    location: Location,
                    language: String
                ): DataResult<Location> = when {
                    language == "zh" -> withContext(NonCancellable) { oldName.await() }
                    offline -> DataResult.Failure(WeatherFailure.NETWORK)
                    else -> DataResult.Success(place.copy(name = "English name"))
                }
            }
            val repo = FakeWeather(
                { DataResult.Success(sample(clock)) },
                { DataResult.Success(forecast(clock)) })
            val vm = WeatherViewModel(repo, sessions, "token", locations, clock)
            vm.onForeground("en"); runCurrent()
            val original = vm.state.value.current.data
            vm.onForeground("zh"); runCurrent()
            repo.now = { DataResult.Failure(WeatherFailure.NETWORK) }
            offline = true; vm.onForeground("en"); runCurrent()
            oldName.complete(DataResult.Success(place.copy(name = "旧中文"))); runCurrent()
            assertEquals(place.name, vm.state.value.location?.name)
            assertTrue(vm.state.value.nameError)
            assertEquals(original, vm.state.value.current.data)
            offline = false; vm.onBackground(); vm.onForeground("en"); runCurrent()
            assertEquals("English name", vm.state.value.location?.name)
            assertFalse(vm.state.value.nameError)
            vm.onBackground()
        }

    @Test
    // 验证当前天气失败时，已有有效预报仍保持内容可见。
    fun partialForecastKeepsContentVisibleWhenCurrentWeatherFails() = runTest(dispatcher) {
        val clock = clock()
        val weather = FakeWeather(
            now = { DataResult.Failure(WeatherFailure.NETWORK) },
            days = { DataResult.Success(forecast(clock)) },
            hours = { DataResult.Success(hourly(clock)) },
        )
        val vm = WeatherViewModel(weather, sessions, "token", clock = clock)
        vm.onForeground("en"); runCurrent()
        val state = vm.state.value
        assertFalse(state.showEmpty)
        assertEquals(7, state.dailyItems.size)
        assertTrue(state.dailyItems.first().today)
        assertTrue(state.hourlyItems.first().current)
        assertEquals(state.today, state.todayForecast?.date)
        assertEquals(zone, state.hourlyItems.first().zone)
        vm.onBackground()
    }

    @Test
    // 验证仅占位预报不被视为有效数据，整页空态仍可见。
    fun placeholderOnlyForecastDoesNotHideFullPageEmptyState() = runTest(dispatcher) {
        val clock = clock()
        val weather = FakeWeather(
            now = { DataResult.Empty },
            days = { DataResult.Success(forecast(clock).copy(days = listOf(
                DailyForecast(LocalDate.now(clock.withZone(zone)), null, null, null)
            ))) },
        )
        val vm = WeatherViewModel(weather, sessions, "token", clock = clock)
        vm.onForeground("en"); runCurrent()
        assertTrue(vm.state.value.showEmpty)
        assertTrue(vm.state.value.retryEnabled)
        vm.onBackground()
    }
}
