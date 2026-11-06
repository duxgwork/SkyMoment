package com.example.skymoment.datalayer.network

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerializationException
import retrofit2.Response
import java.io.IOException
import java.io.InterruptedIOException
import java.time.Clock
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.random.Random

/** 定义传输层失败分类，不引入天气领域依赖。 */
enum class NetworkFailure { NETWORK, TIMEOUT, REJECTED, AUTHENTICATION, QUOTA, RATE_LIMITED, SERVER, INVALID_DATA }

/** 统一表示 HTTP 请求执行结果，保留失败原因和限流时间。 */
sealed interface NetworkResult<out T> {
    /** 携带成功结果的数据，调用方可以继续映射或展示。 */
    data class Success<T>(val body: T) : NetworkResult<T>
    /** 携带失败原因以及可选的恢复时间，不抹掉其他区域的已有数据。 */
    data class Failure(val kind: NetworkFailure, val retryAt: Instant? = null) :
        NetworkResult<Nothing>
}

/** 在多个请求间共享限流截止时间，使用同步更新避免缩短冷却。 */
class RateLimitGate(private val clock: Clock = Clock.systemUTC()) {
    @Volatile
    private var until: Instant? = null

    @Synchronized
    // 返回尚未到期的共享限流时间，过期后允许重新请求。
    fun remaining(): Instant? = until?.takeIf { it > clock.instant() }
    @Synchronized
    // 只延长共享冷却时间，不让较短的新限制覆盖已有较长限制。
    fun restrict(candidate: Instant) {
        if (until == null || candidate > until) until = candidate
    }
}

/** 单次操作最多重试一次且总耗时不超过 45 秒；取消继续向上传播，429 冷却跨请求共享。 */
class RequestExecutor(
    private val clock: Clock = Clock.systemUTC(),
    private val gate: RateLimitGate = RateLimitGate(clock),
    private val retryDelayMillis: () -> Long = { Random.nextLong(500, 1501) },
    // 供应商自行判断长期配额；默认仅按 HTTP 状态和 Retry-After 处理限流。
    private val quotaExceeded: (Response<*>) -> Boolean = { false },
) {
    // 在总体时限内执行请求；仅短暂网络／服务异常最多重试一次，协程取消继续传播。
    suspend fun <T : Any> execute(request: suspend () -> Response<T>): NetworkResult<T> =
        withTimeoutOrNull(45_000) {
            var last: NetworkResult<T> = NetworkResult.Failure(NetworkFailure.NETWORK)
            repeat(2) { attempt ->
                gate.remaining()?.let {
                    return@withTimeoutOrNull NetworkResult.Failure(
                        NetworkFailure.RATE_LIMITED,
                        it
                    )
                }
                last = try {
                    val response = request()
                    if (response.isSuccessful) {
                        response.body()?.let { NetworkResult.Success(it) }
                            ?: NetworkResult.Failure(NetworkFailure.INVALID_DATA)
                    } else {
                        classify(response)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: SerializationException) {
                    NetworkResult.Failure(NetworkFailure.INVALID_DATA)
                } catch (_: InterruptedIOException) {
                    NetworkResult.Failure(NetworkFailure.TIMEOUT)
                } catch (_: IOException) {
                    NetworkResult.Failure(NetworkFailure.NETWORK)
                }
                val kind = (last as? NetworkResult.Failure)?.kind
                if (attempt == 0 && kind in setOf(
                        NetworkFailure.NETWORK,
                        NetworkFailure.TIMEOUT,
                        NetworkFailure.SERVER
                    )
                ) {
                    delay(retryDelayMillis())
                } else {
                    return@withTimeoutOrNull last
                }
            }
            last
        } ?: NetworkResult.Failure(NetworkFailure.TIMEOUT)

    // 按 HTTP 状态分类错误，并安全解析 Retry-After 以建立共享冷却期。
    private fun classify(response: Response<*>): NetworkResult.Failure {
        if (response.code() == 429) {
            if (quotaExceeded(response)) {
                return NetworkResult.Failure(NetworkFailure.QUOTA)
            }
            val header = response.headers()["Retry-After"]
            val until = header?.toLongOrNull()?.takeIf { it >= 0 }
                ?.let { runCatching { clock.instant().plusSeconds(it) }.getOrNull() }
                ?: header?.let {
                    runCatching {
                        ZonedDateTime.parse(
                            it,
                            DateTimeFormatter.RFC_1123_DATE_TIME
                        ).toInstant()
                    }.getOrNull()
                }
                ?: clock.instant().plusSeconds(60)
            val safeUntil = until.coerceAtLeast(clock.instant().plusSeconds(1))
            gate.restrict(safeUntil)
            return NetworkResult.Failure(NetworkFailure.RATE_LIMITED, safeUntil)
        }
        return NetworkResult.Failure(
            when (response.code()) {
                401, 403 -> NetworkFailure.AUTHENTICATION
                in 500..599 -> NetworkFailure.SERVER
                else -> NetworkFailure.REJECTED
            }
        )
    }
}
