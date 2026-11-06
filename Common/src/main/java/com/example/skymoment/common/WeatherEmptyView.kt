package com.example.skymoment.common

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.FrameLayout
import androidx.annotation.StringRes
import androidx.core.view.ViewCompat
import com.example.skymoment.common.databinding.ViewWeatherEmptyBinding

/** 首页和天气详情共用的空态；仅展示原因和操作，不发起定位或网络请求。 */
class WeatherEmptyView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    FrameLayout(context, attrs) {
    private val binding = ViewWeatherEmptyBinding.inflate(LayoutInflater.from(context), this, true)

    init {
        ViewCompat.setAccessibilityHeading(binding.emptyTitle, true)
    }

    // 仅绑定恢复操作，具体重试或设置流程由调用方决定。
    fun setRetryAction(retry: () -> Unit) {
        binding.emptyRetry.setOnClickListener { retry() }
    }

    // 将状态映射到控件内容与可见性，不发起业务数据请求。
    fun render(
        @StringRes title: Int,
        @StringRes message: Int,
        @StringRes retryLabel: Int,
        retryEnabled: Boolean = true
    ) {
        binding.emptyTitle.setText(title)
        binding.emptyMessage.setText(message)
        binding.emptyRetry.setText(retryLabel)
        binding.emptyRetry.isEnabled = retryEnabled
    }

    // 清除当前视图上的回调与适配器引用，避免销毁后持有旧页面。
    fun clear() {
        binding.emptyRetry.setOnClickListener(null)
    }
}
