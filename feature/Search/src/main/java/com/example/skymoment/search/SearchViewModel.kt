package com.example.skymoment.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.skymoment.domain.DataResult
import com.example.skymoment.domain.Location
import com.example.skymoment.domain.LocationSearchRepository
import com.example.skymoment.domain.LocationSessionRepository
import com.example.skymoment.domain.SearchHistoryRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

/**
 * 防抖与显式提交共用请求入口；代次校验阻止已取消的旧响应恢复过期结果。
 * 由 ViewModel 管理查询任务的生命周期。
 */
class SearchViewModel(
    // 注入地点查询接口，便于替换供应商和测试数据。
    private val locations: LocationSearchRepository,
    // 注入跨页面共享的当前地点会话。
    private val sessions: LocationSessionRepository,
    // 注入只持久化搜索词的历史仓储。
    private val history: SearchHistoryRepository,
) : ViewModel() {
    // 初始状态加载已有搜索历史。
    private val mutableState = MutableStateFlow(SearchUiState(history = history.getRecentQueries()))

    // 对渲染层只开放不可变状态视图。
    val state = mutableState.asStateFlow()

    // 保存当前防抖或查询任务，便于新输入取消。
    private var job: Job? = null

    // 用代次区分新旧查询，拒绝迟到响应。
    private var generation = 0

    // 保存正在处理的关键词与语言组合。
    private var pending: Pair<String, String>? = null

    // 保存最近完成的查询，用于避免重复请求。
    private var completed: Pair<String, String>? = null

    // 区分尚在防抖等待还是已经请求网络。
    private var debouncing = false

    // 保证一次页面选择只生成一个会话。
    private var selectionHandled = false

    /*
     * 恢复选择入口并同步有效语言，保留当前搜索结果和历史。
     */
    fun onForeground(language: String) {
        // 每次重新回到搜索页允许新的有效选择。
        selectionHandled = false
        // 检查界面语言变化并在需要时更新结果。
        languageChanged(language)
    }

    /*
     * 使用当前输入与语言立即提交搜索，不再等待输入防抖。
     */
    fun submit() = search(state.value.query, state.value.language)

    /*
     * 回填所选历史词并立即搜索，输入回写由渲染器负责。
     */
    fun selectHistory(query: String) = search(query, state.value.language)

    /*
     * 接收输入变化并使用防抖入口合并连续输入。
     */
    fun queryChanged(query: String, language: String) = request(query, language, 350, false)

    /*
     * 显式提交时先记录关键词，再立即进入统一请求入口。
     */
    fun search(query: String, language: String) {
        // 先记录显式提交的有效搜索词。
        remember(query)
        // 显式操作不等待防抖，立即请求当前关键词。
        request(query, language, 0, false)
    }

    /*
     * 显式重试当前查询，允许绕过已完成结果的去重判断。
     */
    fun retry() = request(state.value.query, state.value.language, 0, true)

    /*
     * 语言变化后重新查询，并使旧语言请求失效。
     */
    fun languageChanged(language: String) {
        // 只有语言变化时才强制更新已有查询。
        if (language != state.value.language) request(state.value.query, language, 0, true)
    }

    /*
     * 统一管理输入校验、防抖、去重、取消和请求代次，防止旧响应回写。
     */
    private fun request(raw: String, language: String, debounceMillis: Long, force: Boolean) {
        // 用于请求和去重的关键词去除首尾空白。
        val query = raw.trim()
        // 以关键词和语言共同标识一次逻辑查询。
        val key = query to language
        // 及时保存原始输入和语言，让页面与用户编辑保持一致。
        mutableState.value = state.value.copy(query = raw, language = language)
        // 普通非空查询才应用已有请求的去重规则。
        if (!force && query.isNotEmpty()) {
            // 合并相同在途请求，但允许立即提交打断防抖等待。
            if (key == pending && job?.isActive == true && !(debouncing && debounceMillis == 0L)) return
            // 已完成且展示成功内容或合法空态时可以复用结果。
            if (key == completed && state.value.phase in setOf(
                    // 成功候选列表可以直接复用。
                    SearchPhase.CONTENT,
                    // 合法空结果同样不因相同输入重复请求。
                    SearchPhase.EMPTY
                )
            // 满足完成结果复用条件时立即结束本次调用。
            ) return
        }
        // 真正新请求分配新代次，使旧结果失效。
        val request = ++generation
        // 取消旧防抖和旧网络请求。
        job?.cancel()
        // 清空旧的在途查询标识。
        pending = null
        // 清空已完成标识，准备记录新请求。
        completed = null
        // 不足两个码点时只更新输入提示，不访问仓储。
        if (query.codePointCount(0, query.length) < 2) {
            // 生成空闲或输入过短的状态快照。
            mutableState.value = state.value.copy(
                // 保留用户输入原文以便编辑。
                query = raw,
                // 空输入恢复空闲，单字输入显示过短提示。
                phase = if (query.isEmpty()) SearchPhase.IDLE else SearchPhase.TOO_SHORT,
                // 清理旧查询结果，避免显示不对应当前输入的地点。
                result = null,
                // 保留当前界面语言供后续提交使用。
                language = language
            )
            // 过短查询到此结束。
            return
        }
        // 记录新请求键，用于合并后续相同输入。
        pending = key
        // 标记是否需要等待文本防抖。
        debouncing = debounceMillis > 0
        // 发布请求中的状态快照。
        mutableState.value = state.value.copy(
            // 沿用原始输入文本显示。
            query = raw,
            // 显示加载状态，告知候选正在更新。
            phase = SearchPhase.LOADING,
            // 清除前一个查询的结果，避免误选旧地点。
            result = null,
            // 记录本次请求采用的语言。
            language = language
        )
        // 启动可被后续输入取消的查询协程。
        job = viewModelScope.launch {
            // 有防抖时延后请求，减少连续输入的网络调用。
            if (debounceMillis > 0) delay(debounceMillis.milliseconds)
            // 等待结束后标记已进入实际查询阶段。
            debouncing = false
            // 调用地点仓储执行变体查询和结果合并。
            val result = locations.searchLocations(query, language)
            // 只有最新代次可以更新当前搜索页面。
            if (request == generation) {
                // 将本次键标记为已完成，供相同查询复用。
                completed = key
                // 清除在途键，避免误判仍有请求运行。
                pending = null
                // 根据领域结果选择页面展示阶段。
                val phase = when (result) {
                    // 失败结果进入可重试错误态。
                    is DataResult.Failure -> SearchPhase.ERROR
                    // 合法无数据进入空结果态。
                    DataResult.Empty -> SearchPhase.EMPTY
                    // 成功但列表为空也按空态处理，有候选才显示内容。
                    is DataResult.Success -> if (result.data.isEmpty()) SearchPhase.EMPTY else SearchPhase.CONTENT
                }
                // 发布新结果时基于当前快照保留其他状态。
                mutableState.value =
                        // 同时更新结果、展示阶段和对应语言。
                    state.value.copy(phase = phase, result = result, language = language)
            }
        }
    }

    /*
     * 拒绝重复点击，记录关键词并创建随机地点会话。
     */
    fun selectLocation(location: Location): String? {
        // 重复选择回调不再创建会话或触发导航。
        if (selectionHandled) return null
        // 先锁定本轮选择，阻止快速连续点击。
        selectionHandled = true
        // 将用于找到该地点的词记入历史。
        remember(state.value.query)
        // 创建新地点会话并返回跨组件传递的随机 token。
        return sessions.createSession(location)
    }

    /*
     * 防抖输入只查询；主动提交或选中地点时才记录，避免保存逐字输入的片段。
     */
    private fun remember(query: String) {
        // 将历史仓储返回的最新词序同步到页面状态。
        mutableState.value = state.value.copy(history = history.recordQuery(query))
    }

    /*
     * 清空持久化历史，并同步更新界面状态。
     */
    fun clearHistory() {
        // 删除持久化搜索词历史。
        history.clear()
        // 立即清空页面历史展示，无需等待重新进入。
        mutableState.value = state.value.copy(history = emptyList())
    }
}
