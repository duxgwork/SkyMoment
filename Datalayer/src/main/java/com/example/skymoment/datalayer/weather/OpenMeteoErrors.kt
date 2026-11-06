package com.example.skymoment.datalayer.weather

import com.example.skymoment.datalayer.network.NetworkFactory
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import retrofit2.Response

/** 集中定义供应商 HTTPS 端点，防止地址散落到 UI 或基础网络层。 */
internal object OpenMeteoEndpoints {
    const val GEOCODING = "https://geocoding-api.open-meteo.com/"
    const val FORECAST = "https://api.open-meteo.com/"
}

/** reason 字段和日／月配额规则属于供应商协议，基础网络层只处理通用 HTTP 行为。 */
internal object OpenMeteoErrors {
    // 解析供应商错误原因，区分日／月配额与短期限流。
    fun quotaExceeded(response: Response<*>): Boolean {
        val reason = runCatching {
            val body = response.errorBody()?.string().orEmpty()
            NetworkFactory.json.parseToJsonElement(body).jsonObject["reason"]
                ?.jsonPrimitive?.content.orEmpty()
        }.getOrDefault("").lowercase()
        return reason.contains("daily") || reason.contains("monthly") || reason.contains("quota")
    }
}
