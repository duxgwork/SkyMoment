package com.example.skymoment.search

import com.example.skymoment.domain.DataResult
import com.example.skymoment.domain.Location

/** 区分空输入、过短、加载、内容、空结果和失败等搜索阶段。 */
enum class SearchPhase { IDLE, TOO_SHORT, LOADING, CONTENT, EMPTY, ERROR }

/** 搜索页不可变快照，包含输入、结果、语言与历史展示信息。 */
data class SearchUiState(
    val query: String = "",
    val phase: SearchPhase = SearchPhase.IDLE,
    val result: DataResult<List<Location>>? = null,
    val language: String = "en",
    val history: List<String> = emptyList(),
) {
    val showHistory: Boolean = query.isBlank() && history.isNotEmpty()
    val locations: List<Location> = (result as? DataResult.Success)?.data.orEmpty()
}
