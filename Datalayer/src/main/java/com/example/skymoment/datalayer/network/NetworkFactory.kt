package com.example.skymoment.datalayer.network

import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit

/**
 * 提供共享的网络客户端和序列化配置，不包含天气业务规则。
 */
object NetworkFactory {
    // 兼容供应商新增字段，避免未知键导致整个响应解析失败。
    val json = Json { ignoreUnknownKeys = true }

    /*
     * 统一配置连接和读取超时，不启用业务日志或磁盘响应缓存。
     */
    fun client(): OkHttpClient = OkHttpClient.Builder()
        // 限制建立连接的等待时间为十秒。
        .connectTimeout(10, TimeUnit.SECONDS)
        // 限制响应读取等待为十五秒。
        .readTimeout(15, TimeUnit.SECONDS)
        // 限制单次 HTTP 调用总耗时为二十秒。
        .callTimeout(20, TimeUnit.SECONDS)
        // 关闭底层额外重试，由 RequestExecutor 统一管理尝试次数。
        .retryOnConnectionFailure(false)
        // 不将带地点的 HTTP 响应存入磁盘缓存。
        .cache(null)
        // 创建供多个 Retrofit 服务复用的客户端。
        .build()

    /*
     * 为指定 HTTPS 基地址创建 Retrofit，并复用统一 JSON 解析规则。
     */
    fun retrofit(baseUrl: String, client: OkHttpClient = client()): Retrofit {
        // 将基地址解析为结构化 URL 以便检查。
        val url: HttpUrl = baseUrl.toHttpUrl()
        // 要求天气服务通过 HTTPS 传输。
        require(url.isHttps) { "A public HTTPS endpoint is required" }
        // 禁止基地址夹带账号、密码或查询参数。
        require(url.username.isEmpty() && url.password.isEmpty() && url.query == null)
        // 绑定已校验的基地址和共享客户端。
        return Retrofit.Builder().baseUrl(url).client(client)
            // 注册 JSON 转换器并完成 Retrofit 创建。
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType())).build()
    }
}
