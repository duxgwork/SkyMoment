package com.example.skymoment.common

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.FrameLayout
import androidx.core.view.ViewCompat
import com.example.skymoment.common.databinding.ViewWeatherLoadingBinding

/** 启动加载仅展示真实请求阶段；不模拟百分比，也不持有定位或天气请求。 */
class WeatherLoadingView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    FrameLayout(context, attrs) {
    private val binding =
        ViewWeatherLoadingBinding.inflate(LayoutInflater.from(context), this, true)

    init {
        ViewCompat.setAccessibilityHeading(binding.loadingTitle, true)
    }

    // 将状态映射到控件内容与可见性，不发起业务数据请求。
    fun render(fetchingWeather: Boolean) {
        binding.loadingTitle.setText(if (fetchingWeather) R.string.loading_local_weather else R.string.locating)
        binding.loadingMessage.setText(if (fetchingWeather) R.string.startup_weather_message else R.string.startup_location_message)
        binding.loadingLocationStep.setText(if (fetchingWeather) R.string.startup_location_ready else R.string.startup_location_step)
        binding.loadingLocationStep.isSelected = !fetchingWeather
        binding.loadingWeatherStep.isSelected = fetchingWeather
    }
}
