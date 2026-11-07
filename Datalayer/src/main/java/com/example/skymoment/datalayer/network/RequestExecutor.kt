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
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds

/**
 * 定义传输层失败分类，不引入天气领域依赖。
 */
enum class NetworkFailure { NETWORK, TIMEOUT, REJECTED, AUTHENTICATION, QUOTA, RATE_LIMITED, SERVER, INVALID_DATA }

/**
 * 统一表示 HTTP 请求执行结果，保留失败原因和限流时间。
 */
sealed interface NetworkResult<out T> {
    /**
     * 携带成功结果的数据，调用方可以继续映射或展示。
     */
    data class Success<T>(val body: T) : NetworkResult<T>

    /**
     * 携带失败原因以及可选的恢复时间，不抹掉其他区域的已有数据。
     */
    data class Failure(val kind: NetworkFailure, val retryAt: Instant? = null) :
    // 失败不携带成功数据，适用于任意响应类型。
        NetworkResult<Nothing>
}

/**
 * 在多个请求间共享限流截止时间，使用同步更新避免缩短冷却。
 */
class RateLimitGate(private val clock: Clock = Clock.systemUTC()) {
    // 确保不同线程立即可见冷却截止时间的更新。
    @Volatile
    // 没有限流记录时允许直接发起请求。
    private var until: Instant? = null

    /*
     * 返回尚未到期的共享限流时间，过期后允许重新请求。
     * 同步读取冷却状态，避免与延长冷却发生竞争。
     */
    @Synchronized
    fun remaining(): Instant? = until?.takeIf { it > clock.instant() }

    /*
     * 只延长共享冷却时间，不让较短的新限制覆盖已有较长限制。
     * 同步更新截止时间，保证比较与赋值不可分割。
     */
    @Synchronized
    fun restrict(candidate: Instant) {
        // 只接受更晚的截止时间，防止并发响应缩短冷却。
        if (until == null || candidate > until) until = candidate
    }
}

/**
 * 单次操作最多重试一次且总耗时不超过 45 秒；取消继续向上传播，429 冷却跨请求共享。
 */
class RequestExecutor(
    // 注入时钟，便于计算截止时间及确定性测试。
    private val clock: Clock = Clock.systemUTC(),
    // 注入可跨请求复用的限流门。
    private val gate: RateLimitGate = RateLimitGate(clock),
    // 重试前随机等待半秒至一点五秒，减少集中重发。
    private val retryDelayMillis: () -> Long = { Random.nextLong(500, 1501) },
    // 错误解析可独立替换，请求生命周期不依赖供应商协议。
    private val errorClassifier: HttpErrorClassifier = DefaultHttpErrorClassifier(),
) {
    /*
     * 在总体时限内执行请求；仅短暂网络／服务异常最多重试一次，协程取消继续传播。
     */
    suspend fun <T : Any> execute(request: suspend () -> Response<T>): NetworkResult<T> =
        // 将请求、重试等待及第二次尝试纳入四十五秒总预算。
        withTimeoutOrNull(45_000.milliseconds) {
            // 保存最近一次结果，初始占位为网络失败。
            var last: NetworkResult<T> = NetworkResult.Failure(NetworkFailure.NETWORK)
            // 首次请求加一次重试，最多执行两次。
            repeat(2) { attempt ->
                // 每次尝试前检查共享冷却是否仍有效。
                gate.remaining()?.let {
                    // 冷却中直接返回失败，不访问远端。
                    return@withTimeoutOrNull NetworkResult.Failure(
                        // 向调用方报告短期限流类型。
                        NetworkFailure.RATE_LIMITED,
                        // 携带现有恢复时间供页面显示倒计时。
                        it
                    )
                }
                last = try {
                    // 执行调用方提供的挂起 HTTP 请求。
                    val response = request()
                    // 仅成功状态码进入正文处理。
                    if (response.isSuccessful) {
                        // 非空正文包装为传输成功结果。
                        response.body()?.let { NetworkResult.Success(it) }
                        // 成功状态却没有正文时报告格式错误。
                            ?: NetworkResult.Failure(NetworkFailure.INVALID_DATA)
                    } else {
                        // 保留状态码和响应头用于错误与限流判断。
                        errorClassifier.classify(response, clock.instant()).also { failure ->
                            if (failure.kind == NetworkFailure.RATE_LIMITED) {
                                failure.retryAt?.let(gate::restrict)
                            }
                        }
                    }
                } catch (cancelled: CancellationException) {
                    // 继续向上抛出取消以终止本次操作。
                    throw cancelled
                } catch (_: SerializationException) {
                    // 报告不可自动重试的数据格式错误。
                    NetworkResult.Failure(NetworkFailure.INVALID_DATA)
                } catch (_: InterruptedIOException) {
                    // 将超时暴露为可识别的网络失败类型。
                    NetworkResult.Failure(NetworkFailure.TIMEOUT)
                } catch (_: IOException) {
                    // 保留网络错误分类供上层决定展示文案。
                    NetworkResult.Failure(NetworkFailure.NETWORK)
                }
                // 只从失败结果提取错误类别，成功时为 null。
                val kind = (last as? NetworkResult.Failure)?.kind
                // 仅首次尝试遇到可恢复错误才安排再次请求。
                if (attempt == 0 && kind in setOf(
                        // 连接或 DNS 等暂时网络故障允许重试。
                        NetworkFailure.NETWORK,
                        // 请求超时允许在总预算内再试一次。
                        NetworkFailure.TIMEOUT,
                        // 服务端五百类错误允许再试一次。
                        NetworkFailure.SERVER
                    )
                ) {
                    // 使用可取消延迟等待重试，避免阻塞线程。
                    delay(retryDelayMillis().milliseconds)
                } else {
                    // 返回当前最终结果，不额外发送请求。
                    return@withTimeoutOrNull last
                }
            }
            // 循环结束时保留最近一次尝试结果。
            last
            // 外层总预算耗尽统一返回超时失败。
        } ?: NetworkResult.Failure(NetworkFailure.TIMEOUT)

}
