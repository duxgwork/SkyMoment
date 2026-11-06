package com.example.skymoment.search

import com.example.skymoment.common.R as WeatherUiR
import com.example.skymoment.common.messageRes
import com.example.skymoment.domain.DataResult
import com.example.skymoment.framework.R as UiR
import com.example.skymoment.framework.StatusKind
import com.example.skymoment.framework.StatusPanel

/** 搜索页的状态提示集中映射，空结果与失败使用不同的重试行为。 */
internal fun StatusPanel.renderSearch(state: SearchUiState, retry: () -> Unit) {
    val kind = when (state.phase) {
        SearchPhase.IDLE -> StatusKind.HIDDEN
        SearchPhase.TOO_SHORT -> StatusKind.IDLE
        SearchPhase.LOADING -> StatusKind.LOADING
        SearchPhase.CONTENT -> StatusKind.HIDDEN
        SearchPhase.EMPTY -> StatusKind.EMPTY
        SearchPhase.ERROR -> StatusKind.ERROR
    }
    val message = when (state.phase) {
        SearchPhase.LOADING -> UiR.string.loading
        SearchPhase.EMPTY -> WeatherUiR.string.search_empty
        SearchPhase.ERROR -> (state.result as? DataResult.Failure)?.reason?.messageRes()
            ?: WeatherUiR.string.request_error

        SearchPhase.TOO_SHORT -> WeatherUiR.string.search_short
        else -> WeatherUiR.string.search_prompt
    }
    render(kind, message, retry = retry.takeIf { kind == StatusKind.ERROR })
}
