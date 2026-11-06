package com.example.skymoment.framework

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import androidx.annotation.StringRes
import androidx.core.view.isVisible
import com.example.skymoment.framework.databinding.ViewStatusPanelBinding

/** 通用状态面板的展示阶段，不包含天气或搜索业务含义。 */
enum class StatusKind { IDLE, LOADING, EMPTY, ERROR, HIDDEN }

/** 只表达状态，不发请求、不持有 Activity 或 ViewModel。 */
class StatusPanel @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {
    private val binding = ViewStatusPanelBinding.inflate(LayoutInflater.from(context), this, true)

    init {
        orientation = VERTICAL
        isSaveEnabled = false
        binding.statusMessage.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }

    // 替换状态说明文本，用于携带动态内容的提示。
    fun setMessage(message: CharSequence) {
        binding.statusMessage.text = message
    }

    // 将状态映射到控件内容与可见性，不发起业务数据请求。
    fun render(
        kind: StatusKind,
        @StringRes message: Int,
        retryEnabled: Boolean = true,
        retry: (() -> Unit)? = null
    ) {
        isVisible = kind != StatusKind.HIDDEN
        binding.statusProgress.isVisible = kind == StatusKind.LOADING
        binding.statusMessage.setText(message)
        binding.statusRetry.isEnabled = retryEnabled
        binding.statusRetry.isVisible =
            kind in setOf(StatusKind.ERROR, StatusKind.EMPTY) && retry != null
        binding.statusRetry.setOnClickListener(if (retry == null) null else OnClickListener { retry() })
    }
}
