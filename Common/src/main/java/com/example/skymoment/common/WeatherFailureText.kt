package com.example.skymoment.common

import androidx.annotation.StringRes
import com.example.skymoment.domain.WeatherFailure

/*
 * 错误文案只在 UI 层翻译，搜索与天气使用相同的三语资源。
 * 把领域错误映射为可随系统语言切换的资源标识。
 */
@StringRes
fun WeatherFailure.messageRes(): Int = when (this) {
    // 网络故障提示检查连接。
    WeatherFailure.NETWORK -> R.string.network_error
    // 超时提示重新请求。
    WeatherFailure.TIMEOUT -> R.string.timeout_error
    // 短期限流提示等待后重试。
    WeatherFailure.RATE_LIMITED -> R.string.rate_error
    // 长期配额耗尽使用独立说明。
    WeatherFailure.QUOTA -> R.string.quota_error
    // 响应格式或指标校验失败使用数据错误文案。
    WeatherFailure.DATA_INVALID -> R.string.invalid_error
    // 请求或鉴权被拒绝时使用配置相关说明。
    WeatherFailure.REQUEST_REJECTED, WeatherFailure.AUTHENTICATION -> R.string.configuration_error
    // 服务不可用及不支持地点查询时使用请求失败说明。
    WeatherFailure.SERVICE_UNAVAILABLE, WeatherFailure.UNSUPPORTED_LOCATION -> R.string.request_error
}
