package com.example.skymoment.datalayer.network

import retrofit2.Response
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * 错误分类策略只返回分类与恢复时间，不持有限流门或发起重试。
 */
fun interface HttpErrorClassifier {
    fun classify(response: Response<*>, now: Instant): NetworkResult.Failure
}

/**
 * 通用 HTTP 规则；供应商可注入长期配额判断或替换整个分类策略。
 */
class DefaultHttpErrorClassifier(
    private val quotaExceeded: (Response<*>) -> Boolean = { false },
) : HttpErrorClassifier {
    /*
     * 按 HTTP 状态分类错误，并安全解析 Retry-After 以计算冷却截止时间。
     */
    override fun classify(response: Response<*>, now: Instant): NetworkResult.Failure {
        // 优先处理需要共享冷却或配额识别的限流响应。
        if (response.code() == 429) {
            // 由供应商策略判断是否已耗尽长期配额。
            if (quotaExceeded(response)) {
                // 长期配额耗尽直接返回，不进入短期自动重试。
                return NetworkResult.Failure(NetworkFailure.QUOTA)
            }
            // 读取服务端建议的重试时刻或等待秒数。
            val header = response.headers()["Retry-After"]
            // 优先将响应头解释为非负整数秒。
            val until = header?.toLongOrNull()?.takeIf { it >= 0 }
                // 安全叠加等待秒数，溢出时允许走回退逻辑。
                ?.let { runCatching { now.plusSeconds(it) }.getOrNull() }
            // 秒数不可用时尝试解析 HTTP 日期形式。
                ?: header?.let {
                    // 容忍日期格式错误，不让错误响应造成崩溃。
                    runCatching {
                        // 按带时区的日期解析服务端恢复时间。
                        ZonedDateTime.parse(
                            // 使用响应头原始文本作为日期输入。
                            it,
                            // 采用 HTTP 常见的 RFC 1123 日期格式。
                            DateTimeFormatter.RFC_1123_DATE_TIME
                            // 转换为与时钟可比较的绝对时刻。
                        ).toInstant()
                        // 日期解析失败时将候选值置空。
                    }.getOrNull()
                }
                // 响应头不可用时默认等待六十秒。
                ?: now.plusSeconds(60)
            // 至少延后一个秒，避免过去日期导致立即重试。
            val safeUntil = until.coerceAtLeast(now.plusSeconds(1))
            // 返回限流错误及最终恢复时刻。
            return NetworkResult.Failure(NetworkFailure.RATE_LIMITED, safeUntil)
        }
        // 其余失败状态统一包装为网络失败结果。
        return NetworkResult.Failure(
            // 依据 HTTP 状态区分鉴权、服务端和请求错误。
            when (response.code()) {
                // 鉴权失败或访问被拒绝不自动重试。
                401, 403 -> NetworkFailure.AUTHENTICATION
                // 五百类状态视为服务端暂时异常。
                in 500..599 -> NetworkFailure.SERVER
                // 其余非成功状态视为请求被拒绝。
                else -> NetworkFailure.REJECTED
            }
        )
    }
}
