package com.example.skymoment.datalayer.network

import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit

/** 提供共享的网络客户端和序列化配置，不包含天气业务规则。 */
object NetworkFactory {
    val json = Json { ignoreUnknownKeys = true }

    // 统一配置连接和读取超时，不启用业务日志或磁盘响应缓存。
    fun client(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .cache(null)
        .build()

    // 为指定 HTTPS 基地址创建 Retrofit，并复用统一 JSON 解析规则。
    fun retrofit(baseUrl: String, client: OkHttpClient = client()): Retrofit {
        val url: HttpUrl = baseUrl.toHttpUrl()
        require(url.isHttps) { "A public HTTPS endpoint is required" }
        require(url.username.isEmpty() && url.password.isEmpty() && url.query == null)
        return Retrofit.Builder().baseUrl(url).client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType())).build()
    }
}
