package com.example.skymoment.weather

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.skymoment.common.R as WeatherUiR
import com.example.skymoment.common.WeatherFormatter
import com.example.skymoment.common.WeatherPresentation
import com.example.skymoment.weather.databinding.ItemForecastBinding

/** 渲染七日预报条目，并根据稳定日期进行列表差量更新。 */
class DailyForecastAdapter(private val formatter: WeatherFormatter) :
    ListAdapter<DailyForecastItemUiState, DailyForecastAdapter.ViewHolder>(Diff) {
    /** 仅持有当前列表项的 ViewBinding，由 RecyclerView 管理复用生命周期。 */
    class ViewHolder(val binding: ItemForecastBinding) : RecyclerView.ViewHolder(binding.root)

    // 创建列表项绑定；由 RecyclerView 负责把根视图附加到父容器。
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(
        ItemForecastBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )

    // 将当前位置的列表数据和操作回调绑定到复用的视图。
    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val row = getItem(position)
        val views = holder.binding
        val context = views.root.context
        views.dayName.text =
            if (row.today) context.getString(WeatherUiR.string.today) else formatter.weekday(row.day.date)
        views.dayDate.text = formatter.day(row.day.date)
        views.daySymbol.text = WeatherPresentation.symbol(row.day.weatherCode)
        views.daySummary.setText(WeatherPresentation.description(row.day.weatherCode))
        views.dayTemperatures.text = context.getString(
            WeatherUiR.string.forecast_temperature_range,
            formatter.temperature(row.day.maximumCelsius),
            formatter.temperature(row.day.minimumCelsius)
        )
    }

    /** 定义稳定条目标识与完整内容比较，减少列表无意义的重绑。 */
    private object Diff : DiffUtil.ItemCallback<DailyForecastItemUiState>() {
        // 比较稳定业务标识，判断列表前后是否为同一项。
        override fun areItemsTheSame(old: DailyForecastItemUiState, new: DailyForecastItemUiState) =
            old.day.date == new.day.date

        // 比较完整展示数据，使标签、数值等变化触发重新绑定。
        override fun areContentsTheSame(old: DailyForecastItemUiState, new: DailyForecastItemUiState) = old == new
    }
}
