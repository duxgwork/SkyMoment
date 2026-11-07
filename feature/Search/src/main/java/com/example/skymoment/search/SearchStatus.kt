package com.example.skymoment.search

import com.example.skymoment.common.messageRes
import com.example.skymoment.domain.DataResult
import com.example.skymoment.framework.StatusKind
import com.example.skymoment.framework.StatusPanel
import com.example.skymoment.common.R as WeatherUiR
import com.example.skymoment.framework.R as UiR

/*
 * 搜索页的状态提示集中映射，空结果与失败使用不同的重试行为。
 */
internal fun StatusPanel.renderSearch(state: SearchUiState, retry: () -> Unit) {
    // 把业务搜索阶段转换为通用面板展示类型。
    val kind = when (state.phase) {
        // 尚未输入时隐藏状态面板。
        SearchPhase.IDLE -> StatusKind.HIDDEN
        // 输入过短时展示普通提示，不视为失败。
        SearchPhase.TOO_SHORT -> StatusKind.IDLE
        // 请求中展示进度状态。
        SearchPhase.LOADING -> StatusKind.LOADING
        // 已有候选时隐藏面板，交给结果列表展示。
        SearchPhase.CONTENT -> StatusKind.HIDDEN
        // 合法无匹配结果显示空态。
        SearchPhase.EMPTY -> StatusKind.EMPTY
        // 请求失败显示错误态。
        SearchPhase.ERROR -> StatusKind.ERROR
    }
    // 按当前阶段选择本地化提示资源。
    val message = when (state.phase) {
        // 加载阶段提示正在请求。
        SearchPhase.LOADING -> UiR.string.loading
        // 空结果提示没有匹配地点。
        SearchPhase.EMPTY -> WeatherUiR.string.search_empty
        // 失败阶段优先使用实际领域错误文案。
        SearchPhase.ERROR -> (state.result as? DataResult.Failure)?.reason?.messageRes()
        // 缺少明确错误类型时使用通用请求失败说明。
            ?: WeatherUiR.string.request_error

        // 输入过短时提示继续输入。
        SearchPhase.TOO_SHORT -> WeatherUiR.string.search_short
        // 其余阶段沿用搜索引导文案。
        else -> WeatherUiR.string.search_prompt
    }
    // 只有错误态提供重试操作，避免其他提示触发无效请求。
    render(kind, message, retry = retry.takeIf { kind == StatusKind.ERROR })
}
