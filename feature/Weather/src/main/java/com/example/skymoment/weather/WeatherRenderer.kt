package com.example.skymoment.weather

import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.skymoment.common.WeatherFormatter
import com.example.skymoment.common.WeatherPresentation
import com.example.skymoment.common.messageRes
import com.example.skymoment.framework.StatusKind
import com.example.skymoment.framework.StatusPanel
import com.example.skymoment.weather.databinding.FragmentWeatherBinding
import java.util.Locale
import com.example.skymoment.common.R as WeatherUiR
import com.example.skymoment.framework.R as UiR

/**
 * 天气视图渲染与 Fragment 生命周期分离，不持有 Activity 或请求仓储。
 */
internal class WeatherRenderer(
    // 持有当前天气布局的类型安全控件集合。
    private val views: FragmentWeatherBinding,
    // 接收用于数值与日期格式化的界面语言。
    locale: Locale,
    // 保存当前天气区域的恢复动作。
    private val retryCurrent: () -> Unit,
    // 保存七日预报区域的恢复动作。
    private val retryForecast: () -> Unit,
    // 保存小时预报区域的恢复动作。
    private val retryHourly: () -> Unit,
    // 接收整个天气页没有可展示数据时的恢复动作。
    retryAll: () -> Unit,
) {
    // 复用视图所属的本地化上下文。
    private val context = views.root.context

    // 统一当前天气与预报的数字和时间格式。
    private val formatter = WeatherFormatter(locale)

    // 为七日预报创建纵向列表适配器。
    private val adapter = DailyForecastAdapter(formatter)

    // 为二十四小时预报创建横向列表适配器。
    private val hourlyAdapter = HourlyForecastAdapter(formatter)

    init {
        // 将全空态操作委托为全部区域刷新。
        views.weatherEmpty.setRetryAction(retryAll)
        // 使用纵向布局展示每日概况。
        views.forecastDays.layoutManager = LinearLayoutManager(context)
        // 连接七日列表与对应适配器。
        views.forecastDays.adapter = adapter
        // 为小时卡片配置独立的列表方向。
        views.forecastHours.layoutManager =
                // 采用从左到右的横向小时列表。
            LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
        // 连接小时列表与对应适配器。
        views.forecastHours.adapter = hourlyAdapter
    }

    /*
     * 将状态映射到控件内容与可见性，不发起业务数据请求。
     */
    fun render(state: WeatherUiState) {
        // 根据派生状态显示或隐藏全页天气空态。
        views.weatherEmpty.isVisible = state.showEmpty
        // 有可展示区域时保留天气内容布局。
        views.weatherContent.isVisible = !state.showEmpty
        // 全页空态时只渲染空态文案和恢复动作。
        if (state.showEmpty) {
            // 更新天气空态组件的本地化内容。
            views.weatherEmpty.render(
                // 使用暂不可用的标题和无数据说明。
                WeatherUiR.string.empty_weather_title, WeatherUiR.string.empty_weather_message,
                // 依据冷却状态控制重新获取天气按钮。
                WeatherUiR.string.empty_retry_weather, state.retryEnabled
            )
            // 空态渲染完成后不再访问下方地点与天气控件。
            return
        }
        // 缺少有效地点时停止内容渲染。
        val location = state.location ?: return
        // 仅有行政路径时显示地点下方的路径行。
        views.placePath.isVisible = location.administrativePath.isNotEmpty()
        // 准备显示真实名称或当前位置占位名称。
        views.placeName.text =
                // 设备地点尚未解析出名称时使用统一标签。
            if (state.showCurrentLocationName) {
                // 显示本地化的当前位置文案。
                context.getString(WeatherUiR.string.current_location)
                // 已有真实名称时直接展示该名称。
            } else location.name
        // 用分隔符展示由细到粗的行政路径。
        views.placePath.text = location.administrativePath.joinToString(" · ")
        // 名称更新失败时显示对应提示。
        views.nameNotice.isVisible = state.nameError
        // 当前温度独立格式化，缺失时使用占位。
        views.temperature.text = formatter.temperature(state.current.data?.temperatureCelsius)
        // 有当前天气时组合图标和文字说明。
        views.description.text = state.current.data?.let {
            // 先根据天气代码及昼夜拼接天气符号。
            "${WeatherPresentation.symbol(it.weatherCode, it.isDay)} ${
                // 从页面上下文读取本地化天气描述。
                context.getString(
                    // 将供应商代码映射为描述资源。
                    WeatherPresentation.description(it.weatherCode)
                )
                // 结束天气符号与说明的组合文本。
            }"
            // 没有当前天气数据时显示统一占位。
        } ?: WeatherPresentation.MISSING_VALUE
        // 独立显示相对湿度，避免受其他指标缺失影响。
        views.humidityValue.text = formatter.humidity(state.current.data?.humidityPercent)
        // 独立显示米每秒风速，保留零风速。
        views.windValue.text = formatter.wind(state.current.data?.windMetresPerSecond)
        // 取当地今天的日预报，用于首屏高低温。
        val today = state.todayForecast
        // 使用本地化模板展示今天的温度范围。
        views.highLow.text = context.getString(
            // 选择今日预报高低温说明资源。
            WeatherUiR.string.today_forecast_high_low,
            // 格式化今天最高温，缺日或缺指标时保留占位。
            formatter.temperature(today?.maximumCelsius),
            // 格式化今天最低温，缺日或缺指标时保留占位。
            formatter.temperature(today?.minimumCelsius)
        )
        // 为当前天气部分指标缺失准备说明文字。
        views.currentNotice.setText(WeatherUiR.string.partial_data)
        // 仅当前天气明确标记部分缺失时显示提示。
        views.currentNotice.isVisible = state.current.data?.partial == true
        // 提交带今天标志的七日列表快照。
        adapter.submitList(state.dailyItems)
        // 提交带时区及当前小时标志的小时列表快照。
        hourlyAdapter.submitList(state.hourlyItems)
        // 独立渲染当前天气加载、失败和空态。
        renderRegion(views.currentStatus, state.current, state.cooldownSeconds) { retryCurrent() }
        // 配置七日预报对应的状态面板。
        renderRegion(
            // 传递日预报状态及共享限流剩余时间。
            views.forecastStatus, state.forecast, state.cooldownSeconds,
            // 把日预报窗口不完整作为可恢复提示。
            incomplete = state.forecast.data?.incomplete == true
            // 日预报面板操作只重新获取日预报。
        ) { retryForecast() }
        // 配置小时预报对应的状态面板。
        renderRegion(
            // 传递小时预报状态及共享限流剩余时间。
            views.hourlyStatus, state.hourly, state.cooldownSeconds,
            // 把小时窗口缺失指标作为不完整状态。
            incomplete = state.hourly.data?.incomplete == true,
            // 小时窗口使用专属的不完整提示文案。
            incompleteMessage = WeatherUiR.string.hourly_forecast_incomplete
            // 小时面板操作只重新获取小时预报。
        ) { retryHourly() }
    }

    /*
     * 分别显示区域加载、失败、空数据或不完整提示，冷却期间禁用重试。
     */
    private fun renderRegion(
        // 接收目标面板、区域快照、冷却及完整性标记。
        panel: StatusPanel,
        region: WeatherRegionUiState<*>,
        cooldown: Long,
        incomplete: Boolean = false,
        // 默认使用日预报不完整文案，并接收区域恢复动作。
        incompleteMessage: Int = WeatherUiR.string.forecast_incomplete,
        retry: () -> Unit
    ) {
        // 按加载、失败、空结果和不完整的顺序决定展示。
        when {
            // 优先呈现当前正在请求或刷新的状态。
            region.loading -> panel.render(
                // 把面板切换为加载类型。
                StatusKind.LOADING,
                // 已有旧数据时提示刷新中，否则提示首次加载。
                if (region.data == null) UiR.string.loading else WeatherUiR.string.refreshing
            )

            // 加载结束后显示区域明确的失败原因。
            region.error != null -> {
                // 把领域错误转为对应语言的消息资源。
                val message = region.error.reason.messageRes()
                // 只有共享冷却结束才允许点击重试。
                panel.render(StatusKind.ERROR, message, cooldown == 0L, retry)
                // 刷新失败但仍有旧数据时补充保留数据的说明。
                if (region.data != null) {
                    // 覆盖通用消息以告知本轮刷新未成功。
                    panel.setMessage(
                        // 从本地化资源构造带具体原因的刷新失败说明。
                        context.getString(
                            // 选用刷新失败提示模板。
                            WeatherUiR.string.refresh_failed,
                            // 把当前错误原因嵌入说明，帮助用户判断恢复方式。
                            context.getString(message)
                        )
                    )
                }
            }

            // 合法空结果显示空态而非网络错误。
            region.empty -> panel.render(
                // 使用可提供恢复操作的空态面板类型。
                StatusKind.EMPTY,
                // 提示当前区域没有可用天气信息。
                WeatherUiR.string.weather_empty,
                // 共享冷却仍有效时禁用空态重试。
                cooldown == 0L,
                // 将按钮委托给该区域自己的重试操作。
                retry
            )

            // 不完整预报保留有效列表，同时提供独立补查操作。
            incomplete -> panel.render(StatusKind.EMPTY, incompleteMessage, cooldown == 0L, retry)
            // 正常完整区域收起状态面板。
            else -> panel.render(StatusKind.HIDDEN, WeatherUiR.string.weather_empty)
        }
    }

    /*
     * 清除当前视图上的回调与适配器引用，避免销毁后持有旧页面。
     */
    fun clear() {
        // 释放全空态按钮持有的页面回调。
        views.weatherEmpty.clear()
        // 解除日预报列表适配器以释放当前视图关联。
        views.forecastDays.adapter = null
        // 解除小时预报列表适配器以释放当前视图关联。
        views.forecastHours.adapter = null
        // 隐藏当前天气状态并清除其旧重试回调。
        views.currentStatus.render(StatusKind.HIDDEN, WeatherUiR.string.weather_empty)
        // 隐藏日预报状态并清除其旧重试回调。
        views.forecastStatus.render(StatusKind.HIDDEN, WeatherUiR.string.weather_empty)
        // 隐藏小时预报状态并清除其旧重试回调。
        views.hourlyStatus.render(StatusKind.HIDDEN, WeatherUiR.string.weather_empty)
    }
}
