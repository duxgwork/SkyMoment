package com.example.skymoment.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.skymoment.domain.DataResult
import com.example.skymoment.domain.Location
import com.example.skymoment.domain.LocationRepository
import com.example.skymoment.domain.LocationSessionRepository
import com.example.skymoment.domain.SearchHistoryRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 防抖与显式提交共用请求入口；代次校验阻止已取消的旧响应恢复过期结果。 */
class SearchViewModel(
    private val locations: LocationRepository,
    private val sessions: LocationSessionRepository,
    private val history: SearchHistoryRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow(SearchUiState(history = history.recent()))
    val state = mutableState.asStateFlow()
    private var job: Job? = null
    private var generation = 0
    private var pending: Pair<String, String>? = null
    private var completed: Pair<String, String>? = null
    private var debouncing = false
    private var selectionHandled = false

    // 恢复选择入口并同步有效语言，保留当前搜索结果和历史。
    fun onForeground(language: String) {
        selectionHandled = false
        languageChanged(language)
    }

    // 使用当前输入与语言立即提交搜索，不再等待输入防抖。
    fun submit() = search(state.value.query, state.value.language)
    // 回填所选历史词并立即搜索，输入回写由渲染器负责。
    fun selectHistory(query: String) = search(query, state.value.language)

    // 接收输入变化并使用防抖入口合并连续输入。
    fun queryChanged(query: String, language: String) = request(query, language, 350, false)
    // 显式提交时先记录关键词，再立即进入统一请求入口。
    fun search(query: String, language: String) {
        remember(query)
        request(query, language, 0, false)
    }

    // 显式重试当前查询，允许绕过已完成结果的去重判断。
    fun retry() = request(state.value.query, state.value.language, 0, true)
    // 语言变化后重新查询，并使旧语言请求失效。
    fun languageChanged(language: String) {
        if (language != state.value.language) request(state.value.query, language, 0, true)
    }

    // 统一管理输入校验、防抖、去重、取消和请求代次，防止旧响应回写。
    private fun request(raw: String, language: String, debounceMillis: Long, force: Boolean) {
        val query = raw.trim()
        val key = query to language
        mutableState.value = state.value.copy(query = raw, language = language)
        if (!force && query.isNotEmpty()) {
            if (key == pending && job?.isActive == true && !(debouncing && debounceMillis == 0L)) return
            if (key == completed && state.value.phase in setOf(
                    SearchPhase.CONTENT,
                    SearchPhase.EMPTY
                )
            ) return
        }
        val request = ++generation
        job?.cancel()
        pending = null
        completed = null
        if (query.codePointCount(0, query.length) < 2) {
            mutableState.value = state.value.copy(
                query = raw,
                phase = if (query.isEmpty()) SearchPhase.IDLE else SearchPhase.TOO_SHORT,
                result = null,
                language = language
            )
            return
        }
        pending = key
        debouncing = debounceMillis > 0
        mutableState.value = state.value.copy(
            query = raw,
            phase = SearchPhase.LOADING,
            result = null,
            language = language
        )
        job = viewModelScope.launch {
            if (debounceMillis > 0) delay(debounceMillis)
            debouncing = false
            val result = locations.search(query, language)
            if (request == generation) {
                completed = key
                pending = null
                val phase = when (result) {
                    is DataResult.Failure -> SearchPhase.ERROR
                    DataResult.Empty -> SearchPhase.EMPTY
                    is DataResult.Success -> if (result.data.isEmpty()) SearchPhase.EMPTY else SearchPhase.CONTENT
                }
                mutableState.value =
                    state.value.copy(phase = phase, result = result, language = language)
            }
        }
    }

    // 拒绝重复点击，记录关键词并创建随机地点会话。
    fun select(location: Location): String? {
        if (selectionHandled) return null
        selectionHandled = true
        remember(state.value.query)
        return sessions.select(location)
    }

    /** 防抖输入只查询；主动提交或选中地点时才记录，避免保存逐字输入的片段。 */
    private fun remember(query: String) {
        mutableState.value = state.value.copy(history = history.record(query))
    }

    // 清空持久化历史，并同步更新界面状态。
    fun clearHistory() {
        history.clear()
        mutableState.value = state.value.copy(history = emptyList())
    }
}
