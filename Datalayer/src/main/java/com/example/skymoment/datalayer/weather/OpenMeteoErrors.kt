package com.example.skymoment.datalayer.weather

import com.example.skymoment.datalayer.network.NetworkFactory
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import retrofit2.Response

/**
 * 集中定义供应商 HTTPS 端点，防止地址散落到 UI 或基础网络层。
 */
internal object OpenMeteoEndpoints {
    // 集中维护地点搜索与 ID 查询的公共 HTTPS 主机。
    const val GEOCODING = "https://geocoding-api.open-meteo.com/"

    // 集中维护当前、小时和七日天气的公共 HTTPS 主机。
    const val FORECAST = "https://api.open-meteo.com/"
}

/**
 * reason 字段和日／月配额规则属于供应商协议，基础网络层只处理通用 HTTP 行为。
 */
internal object OpenMeteoErrors {
    /*
     * 解析供应商错误原因，区分日／月配额与短期限流。
     */
    fun quotaExceeded(response: Response<*>): Boolean {
        // 容忍错误体缺失或损坏，避免错误处理本身抛异常。
        val reason = runCatching {
            // 读取一次错误响应正文，缺失时使用空字符串。
            val body = response.errorBody()?.string().orEmpty()
            // 从 JSON 错误对象中提取供应商原因字段。
            NetworkFactory.json.parseToJsonElement(body).jsonObject["reason"]
                // 仅取原始文本用于配额分类，不直接展示给用户。
                ?.jsonPrimitive?.content.orEmpty()
            // 解析失败时回退为空，并忽略英文大小写差异。
        }.getOrDefault("").lowercase()
        // 将日限额、月限额及明确配额耗尽归入长期配额错误。
        return reason.contains("daily") || reason.contains("monthly") || reason.contains("quota")
    }
}
