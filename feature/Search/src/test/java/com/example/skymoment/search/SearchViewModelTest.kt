package com.example.skymoment.search

import com.example.skymoment.domain.CurrentWeather
import com.example.skymoment.domain.DataResult
import com.example.skymoment.domain.Location
import com.example.skymoment.domain.LocationSearchRepository
import com.example.skymoment.domain.LocationSessionRepository
import com.example.skymoment.domain.SearchHistoryRepository
import com.example.skymoment.domain.WeatherFailure
import java.time.ZoneId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
/** 覆盖 SearchViewModel 的正常流程、恢复行为与边界条件。 */
class SearchViewModelTest {
    @get:org.junit.Rule
    val mainDispatcher = com.example.skymoment.test.MainDispatcherRule()
    private val dispatcher get() = mainDispatcher.dispatcher
    private val place =
        Location("test", "1", "示例区", listOf("示例市"), 1.0, 2.0, ZoneId.of("Asia/Shanghai"))
    private var selected: Location? = null
    private val sessions = object : LocationSessionRepository {
        // 提供可控的会话选择结果，隔离真实网络与系统环境。
        override fun createSession(location: Location, initialWeather: CurrentWeather?): String {
            selected = location; return "token"
        }

        // 提供可控的会话解析结果，隔离真实网络与系统环境。
        override fun findLocation(token: String) = selected
        // 提供可控的数据清理行为，隔离真实网络与系统环境。
        override fun clear() {
            selected = null
        }
    }
    private val history = object : SearchHistoryRepository {
        private var entries = emptyList<String>()
        // 读取并清理历史关键词，按去重后的最近使用顺序返回。
        override fun getRecentQueries() = entries
        // 去除首尾空白，忽略大小写去重并将新词移到队首，最多保留十条。
        override fun recordQuery(query: String): List<String> {
            entries = listOf(query.trim())
            return entries
        }

        // 提供可控的数据清理行为，隔离真实网络与系统环境。
        override fun clear() {
            entries = emptyList()
        }
    }


    // 创建使用可控仓储和时钟的 ViewModel，供测试驱动状态变化。
    private fun model(block: suspend (String, String) -> DataResult<List<Location>>) =
        SearchViewModel(
            object : LocationSearchRepository {
                // 提供可控的搜索响应，隔离真实网络与系统环境。
                override suspend fun searchLocations(query: String, language: String) =
                    block(query, language)
            }, sessions, history,
        )

    @Test
    // 验证最短输入、防抖、立即提交、去重和显式选择符合约定。
    fun trimMinimumDebounceSubmitDedupAndExplicitSelection() = runTest(dispatcher) {
        val calls = mutableListOf<Pair<String, String>>()
        val vm =
            model { query, language -> calls += query to language; DataResult.Success(listOf(place)) }
        vm.queryChanged(" ", "zh"); runCurrent()
        assertEquals(SearchPhase.IDLE, vm.state.value.phase)
        vm.queryChanged("区", "zh"); runCurrent()
        assertEquals(SearchPhase.TOO_SHORT, vm.state.value.phase)
        assertTrue(calls.isEmpty())
        vm.queryChanged("海淀", "zh"); runCurrent(); advanceTimeBy(200)
        vm.queryChanged(" 海淀区 ", "zh"); runCurrent(); advanceTimeBy(349); runCurrent()
        assertTrue(calls.isEmpty())
        vm.search(" 海淀区 ", "zh"); runCurrent()
        assertEquals(listOf("海淀区" to "zh"), calls)
        vm.search("海淀区", "zh"); runCurrent()
        assertEquals(1, calls.size)
        assertNull(selected)
        assertEquals("token", vm.selectLocation(place)); assertEquals(place, selected)
        vm.languageChanged("en"); runCurrent()
        assertEquals("海淀区" to "en", calls.last())
    }

    @Test
    // 验证旧响应不能覆盖新语言或恢复已清空的输入。
    fun lateOldResponseCannotReplaceNewLanguageOrRestoreClearedInput() = runTest(dispatcher) {
        val old = CompletableDeferred<DataResult<List<Location>>>()
        val vm =
            model { _, language -> if (language == "zh") withContext(NonCancellable) { old.await() } else DataResult.Empty }
        vm.search("海淀", "zh"); runCurrent()
        vm.languageChanged("en"); runCurrent()
        assertEquals(SearchPhase.EMPTY, vm.state.value.phase)
        vm.queryChanged("", "en")
        old.complete(DataResult.Success(listOf(place))); runCurrent()
        assertEquals(SearchPhase.IDLE, vm.state.value.phase)
        assertNull(vm.state.value.result)
    }

    @Test
    // 验证仅提交或选择记录历史，查询状态变化不丢历史。
    fun historyRecordsOnlySubmissionOrSelectionAndSurvivesQueryStateChanges() =
        runTest(dispatcher) {
            val vm = model { _, _ -> DataResult.Success(listOf(place)) }
            vm.queryChanged(" London ", "en")
            advanceTimeBy(350); runCurrent()
            assertTrue(vm.state.value.history.isEmpty())
            vm.selectLocation(place)
            assertEquals(listOf("London"), vm.state.value.history)
            vm.queryChanged("", "en"); runCurrent()
            assertEquals(listOf("London"), vm.state.value.history)

            val reopened = model { _, _ -> DataResult.Empty }
            assertEquals(listOf("London"), reopened.state.value.history)
            reopened.search(" Tokyo ", "en"); runCurrent()
            assertEquals(listOf("Tokyo"), reopened.state.value.history)
            reopened.languageChanged("zh"); runCurrent()
            assertEquals(listOf("Tokyo"), reopened.state.value.history)
            reopened.clearHistory()
            assertTrue(reopened.state.value.history.isEmpty())
            assertTrue(history.getRecentQueries().isEmpty())
        }

    @Test
    // 验证失败和空结果保持区别，重试由显式操作触发。
    fun failureAndEmptyRemainDistinctAndRetryIsExplicit() = runTest(dispatcher) {
        var calls = 0
        val vm =
            model { _, _ -> if (++calls == 1) DataResult.Failure(WeatherFailure.NETWORK) else DataResult.Empty }
        vm.search("London", "en"); runCurrent()
        assertEquals(SearchPhase.ERROR, vm.state.value.phase)
        vm.retry(); runCurrent()
        assertEquals(SearchPhase.EMPTY, vm.state.value.phase)
        assertEquals(2, calls)
    }

    @Test
    // 验证重复选择不会多次替换会话，下次前台可重新选择。
    fun repeatedSelectionDoesNotReplaceSessionUntilNextForeground() = runTest(dispatcher) {
        val vm = model { _, _ -> DataResult.Success(listOf(place)) }
        assertEquals("token", vm.selectLocation(place))
        assertNull(vm.selectLocation(place.copy(id = "second")))
        assertEquals(place, selected)
        vm.onForeground("en")
        assertEquals("token", vm.selectLocation(place.copy(id = "second")))
        assertEquals("second", selected?.id)
    }

    @Test
    // 验证选择历史同步更新输入并直接提交，不再等待防抖。
    fun selectingHistoryUpdatesInputAndSubmitsWithoutDebounce() = runTest(dispatcher) {
        val calls = mutableListOf<String>()
        val vm = model { query, _ -> calls += query; DataResult.Success(listOf(place)) }
        vm.queryChanged("Pending", "en")
        vm.selectHistory("London"); runCurrent()
        assertEquals(listOf("London"), calls)
        assertEquals("London", vm.state.value.query)
        assertEquals(listOf(place), vm.state.value.locations)
        advanceTimeBy(350); runCurrent()
        vm.submit(); runCurrent()
        assertEquals(listOf("London"), calls)
    }
}
