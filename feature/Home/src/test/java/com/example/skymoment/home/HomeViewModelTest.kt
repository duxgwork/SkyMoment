package com.example.skymoment.home

import com.example.skymoment.test.FakeLocationSessionRepository
import com.example.skymoment.domain.Coordinates
import com.example.skymoment.domain.CurrentWeather
import com.example.skymoment.domain.DEVICE_LOCATION_PROVIDER
import com.example.skymoment.domain.DataResult
import com.example.skymoment.domain.DeviceLocationProvider
import com.example.skymoment.domain.LocatedWeather
import com.example.skymoment.domain.Location
import com.example.skymoment.domain.WeatherFailure
import com.example.skymoment.domain.WeatherRepository
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
/** 覆盖 HomeViewModel 的正常流程、恢复行为与边界条件。 */
class HomeViewModelTest {
    @get:org.junit.Rule
    val mainDispatcher = com.example.skymoment.test.MainDispatcherRule()
    private val dispatcher get() = mainDispatcher.dispatcher
    private val coordinates = Coordinates(31.2, 121.5)
    private val place = Location(
        DEVICE_LOCATION_PROVIDER, "current", "Current location", emptyList(),
        coordinates.latitude, coordinates.longitude, ZoneId.of("Asia/Shanghai")
    )
    private val current = CurrentWeather(12.0, 50.0, 2.0, 3, true, Instant.parse("2026-11-06T00:00:00Z"), emptyList())
    private val sessions = FakeLocationSessionRepository()
    private var hasPermission = true
    private var locationCalls = 0
    private var weatherCalls = 0
    private var locate: suspend () -> Coordinates? = { coordinates }
    private var query: suspend () -> DataResult<LocatedWeather> =
        { DataResult.Success(LocatedWeather(place, current)) }
    private val device = object : DeviceLocationProvider {
        // 近似或精确位置任一授权即可进入前台定位流程。
        override fun hasPermission() = hasPermission
        // 获取一次前台位置；无可用位置返回 null，取消时释放系统回调。
        override suspend fun locate(): Coordinates? {
            locationCalls++; return locate.invoke()
        }
    }
    private val weather = object : WeatherRepository {
        // 提供可控的定位天气响应，隔离真实网络与系统环境。
        override suspend fun currentAt(
            coordinates: Coordinates,
            language: String
        ): DataResult<LocatedWeather> {
            weatherCalls++; return query()
        }

        // 提供可控的当前天气样本，隔离真实网络与系统环境。
        override suspend fun current(location: Location, language: String) = DataResult.Empty
        // 提供可控的七日预报样本，隔离真实网络与系统环境。
        override suspend fun forecast(location: Location, language: String) = DataResult.Empty
        // 提供可控的小时预报样本，隔离真实网络与系统环境。
        override suspend fun hourly(location: Location, language: String) = DataResult.Empty
    }


    // 创建使用可控仓储和时钟的 ViewModel，供测试驱动状态变化。
    private fun model(token: String? = null) = HomeViewModel(device, weather, sessions, token)

    @Test
    // 验证拒绝权限进入空态且不请求定位或天气。
    fun deniedPermissionFallsBackWithoutRequestingLocationOrWeather() = runTest(dispatcher) {
        val vm = model()
        hasPermission = false
        vm.onForeground("en")
        vm.onPermissionResult("en"); runCurrent()
        assertEquals(HomePhase.FALLBACK, vm.state.value.phase)
        assertEquals(HomeEmptyReason.PERMISSION, vm.state.value.emptyReason)
        assertEquals(0, locationCalls); assertEquals(0, weatherCalls)
    }

    @Test
    // 验证无位置与定位超时均不继续请求天气。
    fun missingLocationAndLocationTimeoutFallBackWithoutWeatherRequests() = runTest(dispatcher) {
        locate = { null }
        val missing = model(); missing.onForeground("en"); runCurrent()
        assertEquals(HomePhase.FALLBACK, missing.state.value.phase)
        assertEquals(HomeEmptyReason.LOCATION, missing.state.value.emptyReason)
        locate = { awaitCancellation() }
        val timedOut = model(); timedOut.onForeground("en"); runCurrent()
        assertEquals(HomePhase.LOCATING, timedOut.state.value.phase)
        advanceTimeBy(15_000); runCurrent()
        assertEquals(HomePhase.FALLBACK, timedOut.state.value.phase)
        assertEquals(0, weatherCalls)
    }

    @Test
    // 验证天气失败、空结果和超时均进入对应空态。
    fun weatherFailureEmptyAndTimeoutFallBack() = runTest(dispatcher) {
        for (result in listOf(DataResult.Empty, DataResult.Failure(WeatherFailure.NETWORK))) {
            query = { result }
            val vm = model(); vm.onForeground("en"); runCurrent()
            assertEquals(HomePhase.FALLBACK, vm.state.value.phase)
            assertEquals(HomeEmptyReason.WEATHER, vm.state.value.emptyReason)
            assertEquals((result as? DataResult.Failure)?.reason, vm.state.value.weatherFailure)
        }
        query = { awaitCancellation() }
        val timedOut = model(); timedOut.onForeground("en"); runCurrent()
        assertEquals(HomePhase.WEATHER, timedOut.state.value.phase)
        advanceTimeBy(30_000); runCurrent()
        assertEquals(HomePhase.FALLBACK, timedOut.state.value.phase)
        assertEquals(WeatherFailure.TIMEOUT, timedOut.state.value.weatherFailure)
    }

    @Test
    // 网络故障恢复后回到前台会自动定位取天气；在途请求及已显示的天气不会被重复启动。
    fun returningAfterNetworkFailureAutomaticallyResumesStartup() = runTest(dispatcher) {
        for (failure in listOf(WeatherFailure.NETWORK, WeatherFailure.TIMEOUT)) {
            query = { DataResult.Failure(failure) }
            val vm = model()
            vm.onForeground("zh-Hans"); runCurrent()
            assertEquals(failure, vm.state.value.weatherFailure)
            val next = CompletableDeferred<DataResult<LocatedWeather>>()
            query = { next.await() }
            val callsBeforeRetry = weatherCalls
            vm.onBackground(changingConfigurations = false)
            vm.onForeground("zh-Hans"); runCurrent()
            vm.onForeground("zh-Hans"); runCurrent()
            assertEquals(HomePhase.WEATHER, vm.state.value.phase)
            assertEquals(callsBeforeRetry + 1, weatherCalls)
            next.complete(DataResult.Success(LocatedWeather(place, current))); runCurrent()
            assertEquals(HomePhase.READY, vm.state.value.phase)
            assertNull(vm.state.value.weatherFailure)
            assertEquals(current, sessions.initialWeather(vm.state.value.sessionToken))
            vm.onForeground("zh-Hans"); runCurrent()
            assertEquals(callsBeforeRetry + 1, weatherCalls)
        }
    }

    @Test
    // 保留每种失败的可解释原因；返回前台不绕过限流、配额或重试非网络错误。
    fun nonNetworkFailuresAndEmptyWeatherWaitForExplicitRetry() = runTest(dispatcher) {
        val results = WeatherFailure.entries
            .filterNot { it == WeatherFailure.NETWORK || it == WeatherFailure.TIMEOUT }
            .map { DataResult.Failure(it) } + DataResult.Empty
        for (result in results) {
            query = { result }
            val vm = model()
            vm.onForeground("en"); runCurrent()
            val fallback = vm.state.value
            val callsBeforeForeground = weatherCalls
            vm.onForeground("en"); runCurrent()
            assertEquals(fallback, vm.state.value)
            assertEquals((result as? DataResult.Failure)?.reason, fallback.weatherFailure)
            assertEquals(callsBeforeForeground, weatherCalls)
        }
    }

    @Test
    // 验证失败重试可以恢复，已就绪天气不因重试而被清空。
    fun retryRestartsFailedStartupAndDoesNotReplaceReadyWeather() = runTest(dispatcher) {
        query = { DataResult.Empty }
        val vm = model(); vm.onForeground("en"); runCurrent()
        assertEquals(HomeEmptyReason.WEATHER, vm.state.value.emptyReason)
        query = { DataResult.Success(LocatedWeather(place, current)) }
        vm.onFallbackAction("en"); runCurrent()
        val ready = vm.state.value
        assertEquals(HomePhase.READY, ready.phase)
        assertNull(ready.emptyReason)
        assertEquals(2, weatherCalls)
        vm.onFallbackAction("en")
        assertEquals(ready, vm.state.value)
    }

    @Test
    // 验证首屏天气复用于恢复会话，不重复执行启动请求。
    fun successSeedsWeatherAndRestorationDoesNotRepeatStartup() = runTest(dispatcher) {
        val vm = model(); vm.onForeground("en"); runCurrent()
        assertEquals(HomePhase.READY, vm.state.value.phase)
        val token = vm.state.value.sessionToken
        assertEquals(place, sessions.resolve(token)); assertEquals(
        current,
        sessions.initialWeather(token)
    )
        vm.onForeground("en"); runCurrent()
        val restored = model(token); restored.onForeground("en"); runCurrent()
        assertEquals(token, restored.state.value.sessionToken)
        assertEquals(1, locationCalls); assertEquals(1, weatherCalls)
        sessions.clear()
        assertEquals(HomePhase.PERMISSION, model(token).state.value.phase)
    }

    @Test
    // 验证已取消自动请求即使迟到，也不能覆盖手动会话。
    fun manualSelectionWinsEvenWhenCancelledStartupReturnsLate() = runTest(dispatcher) {
        val late = CompletableDeferred<DataResult<LocatedWeather>>()
        query = { withContext(NonCancellable) { late.await() } }
        val vm = model(); vm.onForeground("en"); runCurrent()
        val manualPlace = place.copy(provider = "test", id = "selected")
        val manualToken = sessions.select(manualPlace)
        vm.onSessionReceived(manualToken)
        late.complete(DataResult.Success(LocatedWeather(place, current))); runCurrent()
        assertEquals(manualToken, vm.state.value.sessionToken)
        assertEquals(manualPlace, sessions.resolve(manualToken))
    }

    @Test
    // 验证无效路由会话终止旧启动请求并保留空态。
    fun invalidRoutedSessionCancelsPendingStartup() = runTest(dispatcher) {
        val late = CompletableDeferred<DataResult<LocatedWeather>>()
        query = { withContext(NonCancellable) { late.await() } }
        val vm = model()
        vm.onForeground("en"); runCurrent()
        vm.onSessionReceived("expired")
        late.complete(DataResult.Success(LocatedWeather(place, current))); runCurrent()
        assertEquals(HomeUiState(HomePhase.FALLBACK, emptyReason = HomeEmptyReason.LOCATION), vm.state.value)
    }

    @Test
    // 验证退后台取消定位，再次前台可以重新尝试。
    fun leavingForegroundCancelsLocationAndAllowsANewForegroundAttempt() = runTest(dispatcher) {
        var cancelled = false
        locate = {
            try {
                awaitCancellation()
            } finally {
                cancelled = true
            }
        }
        val vm = model(); vm.onForeground("en"); runCurrent()
        vm.onBackground(changingConfigurations = false); runCurrent()
        assertTrue(cancelled); assertEquals(HomePhase.PERMISSION, vm.state.value.phase)
        locate = { coordinates }
        vm.onForeground("en"); runCurrent()
        assertEquals(HomePhase.READY, vm.state.value.phase)
    }

    @Test
    // 验证权限与设置事件只消费一次，设置授权后恢复启动。
    fun permissionAndSettingsEffectsAreConsumedOnceAndGrantResumesStartup() = runTest(dispatcher) {
        hasPermission = false
        val vm = model()
        val effects = mutableListOf<HomeUiEffect>()
        // 先触发再订阅，模拟 Activity 尚未进入 RESUMED。
        vm.onForeground("en")
        vm.onForeground("en")
        val collector = backgroundScope.launch { vm.effects.collect { effects += it } }
        runCurrent()
        assertEquals(listOf(HomeUiEffect.RequestLocationPermission), effects)
        vm.onPermissionResult("en")
        vm.onFallbackAction("en"); runCurrent()
        assertEquals(HomeUiEffect.OpenLocationSettings, effects.last())
        collector.cancel(); runCurrent()
        backgroundScope.launch { vm.effects.collect { effects += it } }
        runCurrent()
        assertEquals(2, effects.size)
        hasPermission = true
        vm.onForeground("en"); runCurrent()
        assertEquals(HomePhase.READY, vm.state.value.phase)
        assertEquals(1, locationCalls)
    }

    @Test
    // 验证旋转保留定位请求，空路由参数不改变状态。
    fun configurationChangeKeepsLocationRequestAndBlankRouteKeepsState() = runTest(dispatcher) {
        val ready = CompletableDeferred<Coordinates>()
        locate = { ready.await() }
        val vm = model()
        vm.onForeground("en"); runCurrent()
        vm.onBackground(changingConfigurations = true)
        vm.onForeground("en")
        vm.onSessionReceived(" ")
        assertEquals(HomePhase.LOCATING, vm.state.value.phase)
        ready.complete(coordinates); runCurrent()
        assertEquals(HomePhase.READY, vm.state.value.phase)
        assertEquals(1, locationCalls)
    }

    @Test
    // 切换期间暂停自动天气，重复点击只发出一次打开事件，迟到响应不能覆盖选择。
    fun changingLocationStopsStartupAndSelectionWinsOverLateWeather() = runTest(dispatcher) {
        val late = CompletableDeferred<DataResult<LocatedWeather>>()
        query = { withContext(NonCancellable) { late.await() } }
        val vm = model()
        val effects = mutableListOf<HomeUiEffect>()
        backgroundScope.launch { vm.effects.collect { effects += it } }
        vm.onForeground("en"); runCurrent()
        assertEquals(HomePhase.WEATHER, vm.state.value.phase)
        vm.onChangeLocationRequested()
        vm.onChangeLocationRequested()
        vm.onForeground("en"); runCurrent()
        assertEquals(listOf(HomeUiEffect.OpenLocationSearch), effects)
        assertEquals(1, locationCalls)
        val chosen = place.copy(provider = "test", id = "chosen")
        val token = sessions.select(chosen)
        // 搜索已创建会话而首页尚未收到结果时，也不能让旧自动请求替换会话。
        late.complete(DataResult.Success(LocatedWeather(place, current))); runCurrent()
        assertEquals(chosen, sessions.resolve(token))
        vm.onLocationSearchResult(token, "en")
        assertEquals(HomeUiState(HomePhase.READY, token), vm.state.value)
        assertFalse(vm.isChangingLocation)
    }

    @Test
    // 已有天气时取消切换不重载，后台重建仍等待结果而不会重新打开搜索。
    fun restoredSelectionWaitsForResultAndCancelKeepsExistingWeather() = runTest(dispatcher) {
        val token = sessions.select(place, current)
        val vm = HomeViewModel(device, weather, sessions, token, restoredChangingLocation = true)
        val effects = mutableListOf<HomeUiEffect>()
        backgroundScope.launch { vm.effects.collect { effects += it } }
        vm.onForeground("en")
        vm.onChangeLocationRequested(); runCurrent()
        assertTrue(effects.isEmpty())
        vm.onLocationSearchResult(null, "en"); runCurrent()
        assertEquals(HomeUiState(HomePhase.READY, token), vm.state.value)
        assertFalse(vm.isChangingLocation)
        assertEquals(0, locationCalls)
        assertEquals(0, weatherCalls)
    }

    @Test
    // 取消切换后恢复被中断的定位；等待期间的授权回调不能提前发起请求。
    fun cancellingSelectionResumesInterruptedLocationOnlyAfterResult() = runTest(dispatcher) {
        locate = { awaitCancellation() }
        val vm = model()
        vm.onForeground("en"); runCurrent()
        vm.onChangeLocationRequested(); runCurrent()
        locate = { coordinates }
        vm.onForeground("en")
        vm.onPermissionResult("en"); runCurrent()
        assertEquals(1, locationCalls)
        vm.onLocationSearchResult(null, "en"); runCurrent()
        assertEquals(HomePhase.READY, vm.state.value.phase)
        assertEquals(2, locationCalls)
    }

    @Test
    // 搜索期间收到拒权结果仍记录空态，取消时不再重复弹出授权窗口。
    fun permissionDeniedDuringSelectionStaysFallbackOnCancel() = runTest(dispatcher) {
        hasPermission = false
        val vm = model()
        val effects = mutableListOf<HomeUiEffect>()
        backgroundScope.launch { vm.effects.collect { effects += it } }
        vm.onForeground("en")
        vm.onChangeLocationRequested()
        vm.onPermissionResult("en")
        vm.onLocationSearchResult(null, "en"); runCurrent()
        assertEquals(HomeEmptyReason.PERMISSION, vm.state.value.emptyReason)
        assertEquals(1, effects.count { it == HomeUiEffect.RequestLocationPermission })
        assertEquals(0, locationCalls)
    }
}
