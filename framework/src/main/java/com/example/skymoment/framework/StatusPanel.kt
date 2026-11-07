package com.example.skymoment.framework

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import androidx.annotation.StringRes
import androidx.core.view.isVisible
import com.example.skymoment.framework.databinding.ViewStatusPanelBinding

/**
 * 通用状态面板的展示阶段，不包含天气或搜索业务含义。
 */
enum class StatusKind { IDLE, LOADING, EMPTY, ERROR, HIDDEN }

/**
 * 只表达状态，不发请求、不持有 Activity 或 ViewModel。
 * 以纵向容器承载加载、消息和重试操作。
 */
class StatusPanel @JvmOverloads constructor(
    // 使用页面语言上下文解析状态文案和样式。
    context: Context,
    // 允许 XML 构造时传入属性集合。
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {
    // 加载状态面板布局并直接挂载到当前控件。
    private val binding = ViewStatusPanelBinding.inflate(LayoutInflater.from(context), this, true)

    init {
        // 将进度、消息和操作按钮按纵向排列。
        orientation = VERTICAL
        // 状态由 ViewModel 重建，不依赖 View 自动保存。
        isSaveEnabled = false
        // 让辅助阅读器在合适时机播报状态消息变化。
        binding.statusMessage.accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE
    }

    /*
     * 替换状态说明文本，用于携带动态内容的提示。
     */
    fun setMessage(message: CharSequence) {
        // 直接更新动态消息，例如限流倒计时。
        binding.statusMessage.text = message
    }

    /*
     * 将状态映射到控件内容与可见性，不发起业务数据请求。
     */
    fun render(
        // 接收隐藏、加载、空态或错误等面板类型。
        kind: StatusKind,
        // 接收当前状态对应的本地化文本资源。
        @StringRes message: Int,
        // 允许调用方在限流等情况下禁用重试。
        retryEnabled: Boolean = true,
        // 可选接收重试动作，缺失时不展示重试入口。
        retry: (() -> Unit)? = null
    ) {
        // 隐藏类型直接收起整个状态面板。
        isVisible = kind != StatusKind.HIDDEN
        // 仅加载阶段显示进度控件。
        binding.statusProgress.isVisible = kind == StatusKind.LOADING
        // 应用当前状态的说明文字。
        binding.statusMessage.setText(message)
        // 按业务状态设置重试按钮是否可操作。
        binding.statusRetry.isEnabled = retryEnabled
        // 按状态及动作可用性决定是否显示按钮。
        binding.statusRetry.isVisible =
                // 仅错误或空态且存在回调时显示重试。
            kind in setOf(StatusKind.ERROR, StatusKind.EMPTY) && retry != null
        // 安装本次重试回调，无回调时移除旧监听。
        binding.statusRetry.setOnClickListener(if (retry == null) null else OnClickListener { retry() })
    }
}
