package com.example.skymoment.datalayer.network

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.Buffer
import okio.GzipSink
import okio.buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Query
import java.net.SocketTimeoutException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@Serializable
/** 用于真实序列化测试的最小响应模型。 */
data class SamplePayload(val value: String)
/** 供 MockWebServer 验证 HTTPS、编码和响应解析的 Retrofit 接口。 */
interface SampleService {
    @GET("sample")
    // 提供可控的接口响应，隔离真实网络与系统环境。
    suspend fun get(@Query("name") name: String = "示例区"): Response<SamplePayload>
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
/** 覆盖 RequestExecutor 的正常流程、恢复行为与边界条件。 */
class RequestExecutorTest {
    private val server = MockWebServer()
    private val clock = Clock.fixed(Instant.parse("2026-11-05T00:00:00Z"), ZoneOffset.UTC)
    private lateinit var service: SampleService
    private lateinit var client: okhttp3.OkHttpClient

    @Before
    // 为本用例准备调度器、时钟或测试服务，隔离外部环境。
    fun setUp() {
        val cert = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val serverCertificates = HandshakeCertificates.Builder().heldCertificate(cert).build()
        server.useHttps(serverCertificates.sslSocketFactory(), false)
        server.start()
        val certificates =
            HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build()
        client = NetworkFactory.client().newBuilder()
            .sslSocketFactory(certificates.sslSocketFactory(), certificates.trustManager).build()
        service = NetworkFactory.retrofit(server.url("/").toString(), client)
            .create(SampleService::class.java)
    }

    @After
    // 恢复全局调度器并释放本用例的测试资源。
    fun tearDown() {
        server.shutdown()
    }

    @Test
    // 验证HTTPS 查询正确编码，压缩响应只解码一次且不附带凭据或磁盘缓存。
    fun httpsEncodedQueryAndGzipAreDecodedOnceWithoutCredentialsOrDiskCache() = runBlocking {
        val bytes = Buffer()
        GzipSink(bytes).buffer().use { it.writeUtf8("""{"value":"合成天气","new_field":1}""") }
        server.enqueue(
            MockResponse().setHeader("Content-Encoding", "gzip")
                .setHeader("Content-Type", "application/json").setBody(bytes)
        )
        val result = RequestExecutor(clock).execute { service.get() }
        assertEquals(NetworkResult.Success(SamplePayload("合成天气")), result)
        val request = server.takeRequest()
        assertEquals("示例区", request.requestUrl!!.queryParameter("name"))
        assertNull(request.getHeader("Authorization"))
        assertNull(request.requestUrl!!.queryParameter("apikey"))
        assertNull(NetworkFactory.client().cache)
        assertFalse(NetworkFactory.client().retryOnConnectionFailure)
        assertEquals(20_000, NetworkFactory.client().callTimeoutMillis)
        assertTrue(runCatching { NetworkFactory.retrofit("http://example.com/") }.isFailure)
    }

    @Test
    // 验证请求拒绝不重试，服务端错误最多重试一次。
    fun rejectedResponsesDoNotRetryButServerFailureRetriesOnce() = runBlocking {
        val executor = RequestExecutor(clock, retryDelayMillis = { 500 })
        for ((status, expected) in listOf(
            400 to NetworkFailure.REJECTED, 401 to NetworkFailure.AUTHENTICATION,
            403 to NetworkFailure.AUTHENTICATION, 404 to NetworkFailure.REJECTED
        )) {
            val before = server.requestCount
            server.enqueue(
                MockResponse().setResponseCode(status)
                    .setBody("""{"error":true,"reason":"private detail"}""")
            )
            assertEquals(NetworkResult.Failure(expected), executor.execute { service.get() })
            assertEquals(before + 1, server.requestCount)
        }
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().setBody("""{"value":"ok"}"""))
        assertEquals(NetworkResult.Success(SamplePayload("ok")), executor.execute { service.get() })
        assertEquals(6, server.requestCount)
    }

    @Test
    // 验证限流时间跨请求共享，长期配额耗尽不重试。
    fun rateLimitIsSharedAndQuotaDoesNotRetry() = runBlocking {
        val executor = RequestExecutor(clock)
        server.enqueue(
            MockResponse().setResponseCode(429).setHeader("Retry-After", "120")
                .setBody("""{"error":true,"reason":"Minutely limit"}""")
        )
        val limited =
            NetworkResult.Failure(NetworkFailure.RATE_LIMITED, clock.instant().plusSeconds(120))
        assertEquals(limited, executor.execute { service.get() })
        assertEquals(limited, executor.execute { service.get("another") })
        assertEquals(1, server.requestCount)
        server.enqueue(
            MockResponse().setResponseCode(429)
                .setBody("""{"error":true,"reason":"Daily API request limit exceeded"}""")
        )
        assertEquals(
            NetworkResult.Failure(NetworkFailure.QUOTA),
            RequestExecutor(clock, errorClassifier = DefaultHttpErrorClassifier { true }).execute { service.get() })
        assertEquals(2, server.requestCount)
    }

    @Test
    // 验证缺少限流时间时采用默认冷却，非法 JSON 不重试。
    fun missingRetryAfterUsesAtLeastSixtySecondsAndMalformedJsonDoesNotRetry() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429))
        assertEquals(
            NetworkResult.Failure(NetworkFailure.RATE_LIMITED, clock.instant().plusSeconds(60)),
            RequestExecutor(clock).execute { service.get() })
        server.enqueue(MockResponse().setBody("{invalid"))
        assertEquals(
            NetworkResult.Failure(NetworkFailure.INVALID_DATA),
            RequestExecutor(clock).execute { service.get() })
        assertEquals(2, server.requestCount)
    }

    @Test
    // 验证超大 Retry-After 安全回退，不发生时间溢出崩溃。
    fun overflowingRetryAfterFallsBackInsteadOfCrashing() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(429).setHeader("Retry-After", Long.MAX_VALUE.toString())
        )
        assertEquals(
            NetworkResult.Failure(NetworkFailure.RATE_LIMITED, clock.instant().plusSeconds(60)),
            RequestExecutor(clock).execute { service.get() })
    }

    @Test
    // 验证停滞请求最多重试一次，取消请求不再重试。
    fun stalledHttpRetriesOnlyOnceAndCancelledHttpDoesNotRetry() = runBlocking {
        val shortClient =
            client.newBuilder().readTimeout(250, java.util.concurrent.TimeUnit.MILLISECONDS).build()
        val shortService = NetworkFactory.retrofit(server.url("/").toString(), shortClient)
            .create(SampleService::class.java)
        repeat(2) { server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE)) }
        assertEquals(
            NetworkResult.Failure(NetworkFailure.TIMEOUT),
            RequestExecutor(clock, retryDelayMillis = { 0 }).execute { shortService.get() })
        assertEquals(2, server.requestCount)
        repeat(2) { server.takeRequest() }
        server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE))
        val job = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            RequestExecutor(clock).execute { service.get() }
        }
        assertNotNull(kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)
        })
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertEquals(3, server.requestCount)
    }

    @Test
    // 验证总体超时有界，协程取消不被转换为业务错误。
    fun timeoutIsBoundedAndCancellationIsNotAnError() = runTest {
        val executor = RequestExecutor(clock, retryDelayMillis = { 500 })
        var attempts = 0
        assertEquals(
            NetworkResult.Failure(NetworkFailure.TIMEOUT),
            executor.execute<SamplePayload> {
                attempts++
                throw SocketTimeoutException()
            })
        assertEquals(2, attempts)
        val before = testScheduler.currentTime
        val result = executor.execute<SamplePayload> { awaitCancellation() }
        assertEquals(NetworkResult.Failure(NetworkFailure.TIMEOUT), result)
        assertEquals(45_000, testScheduler.currentTime - before)
        var cancelled = false
        val operation = launch {
            try {
                executor.execute<SamplePayload> { awaitCancellation() }
            } catch (expected: CancellationException) {
                cancelled = true; throw expected
            }
        }
        runCurrent()
        operation.cancelAndJoin()
        assertTrue(cancelled)
    }
}
