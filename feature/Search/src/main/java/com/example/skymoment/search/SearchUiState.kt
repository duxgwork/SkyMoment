package com.example.skymoment.search

import com.example.skymoment.domain.DataResult
import com.example.skymoment.domain.Location

/**
 * 区分空输入、过短、加载、内容、空结果和失败等搜索阶段。
 */
enum class SearchPhase { IDLE, TOO_SHORT, LOADING, CONTENT, EMPTY, ERROR }

/**
 * 搜索页不可变快照，包含输入、结果、语言与历史展示信息。
 */
data class SearchUiState(
    // 保存输入框原始文本，避免渲染时擅自改变输入。
    val query: String = "",
    // 初始阶段为空闲，尚未发起地点查询。
    val phase: SearchPhase = SearchPhase.IDLE,
    // 保留上次业务结果，区分失败、空态与成功。
    val result: DataResult<List<Location>>? = null,
    // 记录当前查询所用界面语言。
    val language: String = "en",
    // 保存供空输入时展示的最近搜索词。
    val history: List<String> = emptyList(),
) {
    // 仅输入为空白且存在历史时展示历史标签。
    val showHistory: Boolean = query.isBlank() && history.isNotEmpty()

    // 只从成功结果提取候选，其余状态返回空列表。
    val locations: List<Location> = (result as? DataResult.Success)?.data.orEmpty()
}
