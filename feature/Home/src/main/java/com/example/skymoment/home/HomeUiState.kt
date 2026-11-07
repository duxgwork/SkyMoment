package com.example.skymoment.home

import com.example.skymoment.domain.WeatherFailure

/**
 * 首页启动状态机的阶段，按权限、定位、天气、就绪或空态单向推进。
 */
enum class HomePhase { PERMISSION, LOCATING, WEATHER, READY, FALLBACK }

/**
 * 区分权限、定位和天气失败，决定空态文案及恢复操作。
 */
enum class HomeEmptyReason { PERMISSION, LOCATION, WEATHER }

/**
 * 首页不可变状态快照，只保存阶段、随机会话和失败原因。
 */
data class HomeUiState(
    // 记录权限、定位、天气请求、就绪或回退阶段。
    val phase: HomePhase,
    // 仅在天气就绪后保存当前会话标识。
    val sessionToken: String = "",
    // 区分权限、位置及天气失败以选择恢复动作。
    val emptyReason: HomeEmptyReason? = null,
    // 保留天气具体失败类别，避免所有故障都显示成无数据。
    val weatherFailure: WeatherFailure? = null,
) {
    // 把需要显示启动进度的三个阶段统一为加载状态。
    val isLoading: Boolean =
        phase == HomePhase.PERMISSION || phase == HomePhase.LOCATING || phase == HomePhase.WEATHER
}
