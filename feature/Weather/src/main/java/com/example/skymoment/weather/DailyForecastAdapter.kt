package com.example.skymoment.weather

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.skymoment.common.WeatherFormatter
import com.example.skymoment.common.WeatherPresentation
import com.example.skymoment.weather.databinding.ItemForecastBinding
import com.example.skymoment.common.R as WeatherUiR

/**
 * 渲染七日预报条目，并根据稳定日期进行列表差量更新。
 */
class DailyForecastAdapter(
    private val formatter: WeatherFormatter
) : ListAdapter<DailyForecastItemUiState, DailyForecastAdapter.ViewHolder>(Diff) {// 通过差异计算更新每日预报，保留稳定的日期行身份。
    /**
     * 仅持有当前列表项的 ViewBinding，由 RecyclerView 管理复用生命周期。
     */
    class ViewHolder(val binding: ItemForecastBinding) : RecyclerView.ViewHolder(binding.root)

    /*
     * 创建列表项绑定；由 RecyclerView 负责把根视图附加到父容器。
     */
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(
        // 加载一天的预报行布局，由列表负责挂载。
        ItemForecastBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )

    /*
     * 将当前位置的列表数据和操作回调绑定到复用的视图。
     */
    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        // 读取当前行对应的日期与预报展示状态。
        val row = getItem(position)
        // 取得可复用行的类型安全控件引用。
        val views = holder.binding
        // 使用行上下文读取当前语言资源。
        val context = views.root.context
        // 准备显示今天或星期名称。
        views.dayName.text =
                // 当天显示本地化今天标签，其他日期显示对应星期。
            if (row.today) context.getString(WeatherUiR.string.today) else formatter.weekday(row.day.date)
        // 按界面语言显示该行月日。
        views.dayDate.text = formatter.day(row.day.date)
        // 根据日天气代码显示概况符号。
        views.daySymbol.text = WeatherPresentation.symbol(row.day.weatherCode)
        // 根据日天气代码设置本地化天气描述。
        views.daySummary.setText(WeatherPresentation.description(row.day.weatherCode))
        // 通过资源模板统一组合高低温文本。
        views.dayTemperatures.text = context.getString(
            // 使用每日温度范围的本地化格式。
            WeatherUiR.string.forecast_temperature_range,
            // 格式化当天最高温，缺失时显示统一占位。
            formatter.temperature(row.day.maximumCelsius),
            // 格式化当天最低温，缺失时显示统一占位。
            formatter.temperature(row.day.minimumCelsius)
        )
    }

    /**
     * 定义稳定条目标识与完整内容比较，减少列表无意义的重绑。
     */
    private object Diff : DiffUtil.ItemCallback<DailyForecastItemUiState>() {
        /*
         * 比较稳定业务标识，判断列表前后是否为同一项。
         */
        override fun areItemsTheSame(old: DailyForecastItemUiState, new: DailyForecastItemUiState) =
            // 同一日历日期视为同一预报行，指标变化交给内容比较。
            old.day.date == new.day.date

        /*
         * 比较完整展示数据，使标签、数值等变化触发重新绑定。
         */
        override fun areContentsTheSame(
            old: DailyForecastItemUiState,
            new: DailyForecastItemUiState
        ) = old == new
    }
}
