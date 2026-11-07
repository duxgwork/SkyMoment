package com.example.skymoment.home

import androidx.core.view.isVisible
import com.example.skymoment.common.messageRes
import com.example.skymoment.home.databinding.ActivityHomeBinding
import com.example.skymoment.common.R as WeatherUiR

/**
 * 只负责控件绑定与资源映射，不判断启动或重试流程。
 */
internal class HomeRenderer(
    // 持有当前首页视图，不负责业务状态计算。
    private val views: ActivityHomeBinding,
    // 接收进入地点搜索的意图回调。
    changeLocation: () -> Unit,
    // 接收空态重试或前往设置的意图回调。
    fallbackAction: () -> Unit,
) {
    init {
        // 把右上角切换点击转交页面状态机。
        views.homeChangeLocation.setOnClickListener { changeLocation() }
        // 把空态按钮操作转交页面状态机。
        views.homeEmpty.setRetryAction(fallbackAction)
    }

    /*
     * 将状态映射到控件内容与可见性，不发起业务数据请求。
     */
    fun render(state: HomeUiState) {
        // 权限、定位和天气请求阶段展示启动加载视图。
        views.homeStartup.isVisible = state.isLoading
        // 根据阶段显示定位中或天气请求中的步骤提示。
        if (state.isLoading) views.homeStartup.render(fetchingWeather = state.phase == HomePhase.WEATHER)
        // 只有回退阶段展示可操作的空态。
        views.homeEmpty.isVisible = state.phase == HomePhase.FALLBACK
        // 天气会话就绪时展示 Fragment 容器。
        views.homeWeather.isVisible = state.phase == HomePhase.READY
        // 按失败来源选择不同标题与恢复说明。
        when (state.emptyReason) {
            // 权限失败使用授权引导文案。
            HomeEmptyReason.PERMISSION -> views.homeEmpty.render(
                // 展示需要位置权限的标题。
                WeatherUiR.string.empty_permission_title,
                // 解释授权目的和恢复方式。
                WeatherUiR.string.empty_permission_message,
                // 把操作按钮标为开启位置权限。
                WeatherUiR.string.empty_enable_location
            )
            // 天气失败使用天气空态容器。
            HomeEmptyReason.WEATHER -> views.homeEmpty.render(
                // 展示天气暂不可用标题。
                WeatherUiR.string.empty_weather_title,
                // 优先显示具体网络或服务错误，无明确失败时展示空数据说明。
                state.weatherFailure?.messageRes() ?: WeatherUiR.string.empty_weather_message,
                // 提供重新获取天气的操作文案。
                WeatherUiR.string.empty_retry_weather
            )
            // 其余空态按位置不可用处理。
            else -> views.homeEmpty.render(
                // 展示未能获取位置的标题。
                WeatherUiR.string.empty_location_title,
                // 说明定位失败及恢复建议。
                WeatherUiR.string.empty_location_message,
                // 提供重新定位的操作文案。
                WeatherUiR.string.empty_retry_location
            )
        }
    }

    /*
     * 清除当前视图上的回调与适配器引用，避免销毁后持有旧页面。
     */
    fun clear() {
        // 移除地点切换监听，释放页面回调。
        views.homeChangeLocation.setOnClickListener(null)
        // 清除空态控件中的恢复动作引用。
        views.homeEmpty.clear()
    }
}
