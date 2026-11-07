package com.example.skymoment.home

import androidx.core.view.isVisible
import com.example.skymoment.common.messageRes
import com.example.skymoment.common.R as WeatherUiR
import com.example.skymoment.home.databinding.ActivityHomeBinding

/** 只负责控件绑定与资源映射，不判断启动或重试流程。 */
internal class HomeRenderer(
    private val views: ActivityHomeBinding,
    changeLocation: () -> Unit,
    fallbackAction: () -> Unit,
) {
    init {
        views.homeChangeLocation.setOnClickListener { changeLocation() }
        views.homeEmpty.setRetryAction(fallbackAction)
    }

    // 将状态映射到控件内容与可见性，不发起业务数据请求。
    fun render(state: HomeUiState) {
        views.homeStartup.isVisible = state.isLoading
        if (state.isLoading) views.homeStartup.render(fetchingWeather = state.phase == HomePhase.WEATHER)
        views.homeEmpty.isVisible = state.phase == HomePhase.FALLBACK
        views.homeWeather.isVisible = state.phase == HomePhase.READY
        when (state.emptyReason) {
            HomeEmptyReason.PERMISSION -> views.homeEmpty.render(
                WeatherUiR.string.empty_permission_title,
                WeatherUiR.string.empty_permission_message,
                WeatherUiR.string.empty_enable_location
            )
            HomeEmptyReason.WEATHER -> views.homeEmpty.render(
                WeatherUiR.string.empty_weather_title,
                state.weatherFailure?.messageRes() ?: WeatherUiR.string.empty_weather_message,
                WeatherUiR.string.empty_retry_weather
            )
            else -> views.homeEmpty.render(
                WeatherUiR.string.empty_location_title,
                WeatherUiR.string.empty_location_message,
                WeatherUiR.string.empty_retry_location
            )
        }
    }
    // 清除当前视图上的回调与适配器引用，避免销毁后持有旧页面。
    fun clear() {
        views.homeChangeLocation.setOnClickListener(null)
        views.homeEmpty.clear()
    }
}
