package com.example.skymoment.home

/** 首页启动状态机的阶段，按权限、定位、天气、就绪或空态单向推进。 */
enum class HomePhase { PERMISSION, LOCATING, WEATHER, READY, FALLBACK }
/** 区分权限、定位和天气失败，决定空态文案及恢复操作。 */
enum class HomeEmptyReason { PERMISSION, LOCATION, WEATHER }

/** 首页不可变状态快照，只保存阶段、随机会话和失败原因。 */
data class HomeUiState(
    val phase: HomePhase,
    val sessionToken: String = "",
    val emptyReason: HomeEmptyReason? = null,
) {
    val isLoading: Boolean = phase == HomePhase.PERMISSION || phase == HomePhase.LOCATING || phase == HomePhase.WEATHER
}
