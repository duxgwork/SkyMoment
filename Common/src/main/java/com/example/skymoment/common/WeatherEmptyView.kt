package com.example.skymoment.common

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.FrameLayout
import androidx.annotation.StringRes
import androidx.core.view.ViewCompat
import com.example.skymoment.common.databinding.ViewWeatherEmptyBinding

/**
 * 首页和天气详情共用的空态；仅展示原因和操作，不发起定位或网络请求。
 */
class WeatherEmptyView @JvmOverloads constructor(
    context: Context, attrs:
    AttributeSet? = null
) : FrameLayout(context, attrs) {
    // 加载并挂载空态 ViewBinding，统一访问标题、说明和重试按钮。
    private val binding = ViewWeatherEmptyBinding.inflate(LayoutInflater.from(context), this, true)

    init {
        // 将空态标题标记为无障碍标题，便于辅助导航。
        ViewCompat.setAccessibilityHeading(binding.emptyTitle, true)
    }

    /*
     * 仅绑定恢复操作，具体重试或设置流程由调用方决定。
     */
    fun setRetryAction(retry: () -> Unit) {
        // 将用户点击委托给页面提供的恢复动作。
        binding.emptyRetry.setOnClickListener { retry() }
    }

    /*
     * 将状态映射到控件内容与可见性，不发起业务数据请求。
     */
    fun render(
        // 接收当前失败类型对应的标题资源。
        @StringRes title: Int,
        // 接收权限、定位或天气失败的说明资源。
        @StringRes message: Int,
        // 接收本场景应展示的操作按钮文案。
        @StringRes retryLabel: Int,
        // 允许调用方在暂不可重试时禁用按钮。
        retryEnabled: Boolean = true
    ) {
        // 应用当前空态标题。
        binding.emptyTitle.setText(title)
        // 应用具体失败原因或引导说明。
        binding.emptyMessage.setText(message)
        // 更新重试或前往设置按钮文案。
        binding.emptyRetry.setText(retryLabel)
        // 按调用方状态控制恢复操作可用性。
        binding.emptyRetry.isEnabled = retryEnabled
    }

    /*
     * 清除当前视图上的回调与适配器引用，避免销毁后持有旧页面。
     */
    fun clear() {
        // 解除外部恢复动作引用，避免持有已销毁页面。
        binding.emptyRetry.setOnClickListener(null)
    }
}
