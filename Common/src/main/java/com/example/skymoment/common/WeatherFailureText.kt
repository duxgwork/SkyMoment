package com.example.skymoment.common

import androidx.annotation.StringRes
import com.example.skymoment.domain.WeatherFailure

/** 错误文案只在 UI 层翻译，搜索与天气使用相同的三语资源。 */
@StringRes
fun WeatherFailure.messageRes(): Int = when (this) {
    WeatherFailure.NETWORK -> R.string.network_error
    WeatherFailure.TIMEOUT -> R.string.timeout_error
    WeatherFailure.RATE_LIMITED -> R.string.rate_error
    WeatherFailure.QUOTA -> R.string.quota_error
    WeatherFailure.DATA_INVALID -> R.string.invalid_error
    WeatherFailure.REQUEST_REJECTED, WeatherFailure.AUTHENTICATION -> R.string.configuration_error
    WeatherFailure.SERVICE_UNAVAILABLE, WeatherFailure.UNSUPPORTED_LOCATION -> R.string.request_error
}
