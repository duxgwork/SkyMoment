package com.example.skymoment.weather

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.skymoment.common.R as WeatherUiR
import com.example.skymoment.common.WeatherFormatter
import com.example.skymoment.common.WeatherPresentation
import com.example.skymoment.weather.databinding.ItemHourlyForecastBinding

/** 渲染小时预报条目，并根据绝对时刻进行列表差量更新。 */
class HourlyForecastAdapter(private val formatter: WeatherFormatter) :
    ListAdapter<HourlyForecastItemUiState, HourlyForecastAdapter.ViewHolder>(Diff) {
    /** 仅持有当前列表项的 ViewBinding，由 RecyclerView 管理复用生命周期。 */
    class ViewHolder(val binding: ItemHourlyForecastBinding) : RecyclerView.ViewHolder(binding.root)

    // 创建列表项绑定；由 RecyclerView 负责把根视图附加到父容器。
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(
        ItemHourlyForecastBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )

    // 将当前位置的列表数据和操作回调绑定到复用的视图。
    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val row = getItem(position)
        val views = holder.binding
        val context = views.root.context
        val date = row.hour.time.atZone(row.zone).toLocalDate()
        views.hourTime.text = formatter.hour(row.hour.time, row.zone)
        views.hourDate.text = when {
            row.current -> context.getString(WeatherUiR.string.current_hour)
            date == row.today -> context.getString(WeatherUiR.string.today)
            else -> formatter.day(date)
        }
        views.hourSymbol.text =
            row.hour.weatherCode?.let { WeatherPresentation.symbol(it, row.hour.isDay) }
                ?: WeatherPresentation.MISSING_VALUE
        views.hourSummary.text =
            row.hour.weatherCode?.let { context.getString(WeatherPresentation.description(it)) }
                ?: WeatherPresentation.MISSING_VALUE
        views.hourTemperature.text = formatter.temperature(row.hour.temperatureCelsius)
    }

    /** 定义稳定条目标识与完整内容比较，减少列表无意义的重绑。 */
    private object Diff : DiffUtil.ItemCallback<HourlyForecastItemUiState>() {
        // 比较稳定业务标识，判断列表前后是否为同一项。
        override fun areItemsTheSame(old: HourlyForecastItemUiState, new: HourlyForecastItemUiState) =
            old.hour.time == new.hour.time

        // 比较完整展示数据，使标签、数值等变化触发重新绑定。
        override fun areContentsTheSame(old: HourlyForecastItemUiState, new: HourlyForecastItemUiState) = old == new
    }
}
