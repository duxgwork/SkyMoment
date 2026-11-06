package com.example.skymoment.weather

import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.skymoment.common.R as WeatherUiR
import com.example.skymoment.common.WeatherFormatter
import com.example.skymoment.common.WeatherPresentation
import com.example.skymoment.common.messageRes
import com.example.skymoment.framework.R as UiR
import com.example.skymoment.framework.StatusKind
import com.example.skymoment.framework.StatusPanel
import com.example.skymoment.weather.databinding.FragmentWeatherBinding
import java.util.Locale

/** 天气视图渲染与 Fragment 生命周期分离，不持有 Activity 或请求仓储。 */
internal class WeatherRenderer(
    private val views: FragmentWeatherBinding,
    locale: Locale,
    private val retryCurrent: () -> Unit,
    private val retryForecast: () -> Unit,
    private val retryHourly: () -> Unit,
    retryAll: () -> Unit,
) {
    private val context = views.root.context
    private val formatter = WeatherFormatter(locale)
    private val adapter = DailyForecastAdapter(formatter)
    private val hourlyAdapter = HourlyForecastAdapter(formatter)

    init {
        views.weatherEmpty.setRetryAction(retryAll)
        views.forecastDays.layoutManager = LinearLayoutManager(context)
        views.forecastDays.adapter = adapter
        views.forecastHours.layoutManager =
            LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
        views.forecastHours.adapter = hourlyAdapter
    }

    // 将状态映射到控件内容与可见性，不发起业务数据请求。
    fun render(state: WeatherUiState) {
        views.weatherEmpty.isVisible = state.showEmpty
        views.weatherContent.isVisible = !state.showEmpty
        if (state.showEmpty) {
            views.weatherEmpty.render(
                WeatherUiR.string.empty_weather_title, WeatherUiR.string.empty_weather_message,
                WeatherUiR.string.empty_retry_weather, state.retryEnabled
            )
            return
        }
        val location = state.location ?: return
        views.placePath.isVisible = location.administrativePath.isNotEmpty()
        views.placeName.text =
            if (state.showCurrentLocationName) {
                context.getString(WeatherUiR.string.current_location)
            } else location.name
        views.placePath.text = location.administrativePath.joinToString(" · ")
        views.nameNotice.isVisible = state.nameError
        views.temperature.text = formatter.temperature(state.current.data?.temperatureCelsius)
        views.description.text = state.current.data?.let {
            "${WeatherPresentation.symbol(it.weatherCode, it.isDay)} ${
                context.getString(
                    WeatherPresentation.description(it.weatherCode)
                )
            }"
        } ?: WeatherPresentation.MISSING_VALUE
        views.humidityValue.text = formatter.humidity(state.current.data?.humidityPercent)
        views.windValue.text = formatter.wind(state.current.data?.windMetresPerSecond)
        val today = state.todayForecast
        views.highLow.text = context.getString(
            WeatherUiR.string.today_forecast_high_low,
            formatter.temperature(today?.maximumCelsius),
            formatter.temperature(today?.minimumCelsius)
        )
        views.currentNotice.setText(WeatherUiR.string.partial_data)
        views.currentNotice.isVisible = state.current.data?.partial == true
        adapter.submitList(state.dailyItems)
        hourlyAdapter.submitList(state.hourlyItems)
        renderRegion(views.currentStatus, state.current, state.cooldownSeconds) { retryCurrent() }
        renderRegion(
            views.forecastStatus, state.forecast, state.cooldownSeconds,
            incomplete = state.forecast.data?.incomplete == true
        ) { retryForecast() }
        renderRegion(
            views.hourlyStatus, state.hourly, state.cooldownSeconds,
            incomplete = state.hourly.data?.incomplete == true,
            incompleteMessage = WeatherUiR.string.hourly_forecast_incomplete
        ) { retryHourly() }
    }

    // 分别显示区域加载、失败、空数据或不完整提示，冷却期间禁用重试。
    private fun renderRegion(
        panel: StatusPanel, region: WeatherRegionUiState<*>, cooldown: Long, incomplete: Boolean = false,
        incompleteMessage: Int = WeatherUiR.string.forecast_incomplete, retry: () -> Unit
    ) {
        when {
            region.loading -> panel.render(
                StatusKind.LOADING,
                if (region.data == null) UiR.string.loading else WeatherUiR.string.refreshing
            )

            region.error != null -> {
                val message = region.error.reason.messageRes()
                panel.render(StatusKind.ERROR, message, cooldown == 0L, retry)
                if (region.data != null) {
                    panel.setMessage(
                        context.getString(
                            WeatherUiR.string.refresh_failed,
                            context.getString(message)
                        )
                    )
                }
            }

            region.empty -> panel.render(
                StatusKind.EMPTY,
                WeatherUiR.string.weather_empty,
                cooldown == 0L,
                retry
            )

            incomplete -> panel.render(StatusKind.EMPTY, incompleteMessage, cooldown == 0L, retry)
            else -> panel.render(StatusKind.HIDDEN, WeatherUiR.string.weather_empty)
        }
    }

    // 清除当前视图上的回调与适配器引用，避免销毁后持有旧页面。
    fun clear() {
        views.weatherEmpty.clear()
        views.forecastDays.adapter = null
        views.forecastHours.adapter = null
        views.currentStatus.render(StatusKind.HIDDEN, WeatherUiR.string.weather_empty)
        views.forecastStatus.render(StatusKind.HIDDEN, WeatherUiR.string.weather_empty)
        views.hourlyStatus.render(StatusKind.HIDDEN, WeatherUiR.string.weather_empty)
    }
}
