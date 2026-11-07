package com.example.skymoment.weather

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.skymoment.common.WeatherFormatter
import com.example.skymoment.common.WeatherPresentation
import com.example.skymoment.weather.databinding.ItemHourlyForecastBinding
import com.example.skymoment.common.R as WeatherUiR

/**
 * 渲染小时预报条目，并根据绝对时刻进行列表差量更新，支持夏令时重复小时。
 */
class HourlyForecastAdapter(
    private val formatter: WeatherFormatter
) : ListAdapter<HourlyForecastItemUiState, HourlyForecastAdapter.ViewHolder>(Diff) {
    /**
     * 仅持有当前列表项的 ViewBinding，由 RecyclerView 管理复用生命周期。
     */
    class ViewHolder(val binding: ItemHourlyForecastBinding) : RecyclerView.ViewHolder(binding.root)

    /*
     * 创建列表项绑定；由 RecyclerView 负责把根视图附加到父容器。
     */
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(
        // 加载横向小时卡片布局，由列表负责挂载。
        ItemHourlyForecastBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )

    /*
     * 将当前位置的列表数据和操作回调绑定到复用的视图。
     */
    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        // 读取该位置的小时预报及展示上下文。
        val row = getItem(position)
        // 获取当前小时卡片控件引用。
        val views = holder.binding
        // 使用行上下文解析本地化日期提示。
        val context = views.root.context
        // 把绝对时刻转换为地点当地日期。
        val date = row.hour.time.atZone(row.zone).toLocalDate()
        // 显示地点时区下的小时标签。
        views.hourTime.text = formatter.hour(row.hour.time, row.zone)
        // 按当前小时、今天或其他日期选择辅助标签。
        views.hourDate.text = when {
            // 当前窗口起点显示当前时段标签。
            row.current -> context.getString(WeatherUiR.string.current_hour)
            // 同一天的其余小时显示今天。
            date == row.today -> context.getString(WeatherUiR.string.today)
            // 跨日小时显示其当地月日，避免日期混淆。
            else -> formatter.day(date)
        }
        // 准备显示小时天气符号或缺失占位。
        views.hourSymbol.text =
                // 有天气代码时结合昼夜决定图标。
            row.hour.weatherCode?.let { WeatherPresentation.symbol(it, row.hour.isDay) }
                    // 无天气代码时显示占位，不伪造晴雨情况。
                ?: WeatherPresentation.MISSING_VALUE
        // 准备显示小时天气描述。
        views.hourSummary.text =
                // 有天气代码时读取对应的本地化文案。
            row.hour.weatherCode?.let { context.getString(WeatherPresentation.description(it)) }
                    // 缺失代码使用统一占位说明。
                ?: WeatherPresentation.MISSING_VALUE
        // 格式化该小时温度，保留零值与缺失的区别。
        views.hourTemperature.text = formatter.temperature(row.hour.temperatureCelsius)
    }

    /**
     * 定义稳定条目标识与完整内容比较，减少列表无意义的重绑。
     */
    private object Diff : DiffUtil.ItemCallback<HourlyForecastItemUiState>() {
        /*
         * 比较稳定业务标识，判断列表前后是否为同一项。
         */
        override fun areItemsTheSame(
            old: HourlyForecastItemUiState,
            new: HourlyForecastItemUiState
        ) =
            // 用绝对时刻识别同一小时，避免当地重名小时混淆。
            old.hour.time == new.hour.time

        /*
         * 比较完整展示数据，使标签、数值等变化触发重新绑定。
         */
        override fun areContentsTheSame(
            old: HourlyForecastItemUiState,
            new: HourlyForecastItemUiState
        ) = old == new
    }
}
