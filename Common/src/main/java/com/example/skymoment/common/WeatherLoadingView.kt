package com.example.skymoment.common

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.FrameLayout
import androidx.core.view.ViewCompat
import com.example.skymoment.common.databinding.ViewWeatherLoadingBinding

/**
 * 启动加载仅展示真实请求阶段；不模拟百分比，也不持有定位或天气请求。
 */
class WeatherLoadingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {
    // 保存类型安全的加载视图引用。
    private val binding =
        // 加载并挂载定位与天气两个阶段的提示控件。
        ViewWeatherLoadingBinding.inflate(LayoutInflater.from(context), this, true)

    init {
        // 将加载标题标记为无障碍标题。
        ViewCompat.setAccessibilityHeading(binding.loadingTitle, true)
    }

    /*
     * 将状态映射到控件内容与可见性，不发起业务数据请求。
     */
    fun render(fetchingWeather: Boolean) {
        // 按阶段切换正在定位或正在获取当地天气的标题。
        binding.loadingTitle.setText(if (fetchingWeather) R.string.loading_local_weather else R.string.locating)
        // 展示当前阶段的说明文字。
        binding.loadingMessage.setText(if (fetchingWeather) R.string.startup_weather_message else R.string.startup_location_message)
        // 进入天气阶段后把定位步骤标记为已完成。
        binding.loadingLocationStep.setText(if (fetchingWeather) R.string.startup_location_ready else R.string.startup_location_step)
        // 定位尚未完成时突出当前定位步骤。
        binding.loadingLocationStep.isSelected = !fetchingWeather
        // 进入天气请求阶段后突出天气步骤。
        binding.loadingWeatherStep.isSelected = fetchingWeather
    }
}
