package com.example.skymoment.home

/** 由 Activity 执行的平台操作；流程判断保留在 ViewModel。 */
sealed interface HomeUiEffect {
    /** 打开独立地点搜索页，选择结果返回当前首页。 */
    data object OpenLocationSearch : HomeUiEffect
    /** 请求界面层显示系统定位授权窗口。 */
    data object RequestLocationPermission : HomeUiEffect
    /** 请求界面层打开当前应用的系统设置。 */
    data object OpenLocationSettings : HomeUiEffect
}
