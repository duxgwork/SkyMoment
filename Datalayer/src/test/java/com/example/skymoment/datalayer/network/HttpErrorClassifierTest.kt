package com.example.skymoment.datalayer.network

import kotlinx.coroutines.test.runTest
import okhttp3.Headers
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Test
import retrofit2.Response
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

class HttpErrorClassifierTest {
    private val now = Instant.parse("2026-11-07T00:00:00Z")

    private fun response(status: Int, retryAfter: String): Response<String> = Response.error(
        "{}".toResponseBody(),
        okhttp3.Response.Builder().request(okhttp3.Request.Builder().url("https://example.com").build())
            .protocol(okhttp3.Protocol.HTTP_1_1).code(status).message("failure")
            .headers(Headers.headersOf("Retry-After", retryAfter)).build(),
    )

    @Test fun retryAfterSupportsHttpDateAndClampsPastDates() {
        val classifier = DefaultHttpErrorClassifier()
        for (seconds in listOf(120L, -120L)) {
            val header = DateTimeFormatter.RFC_1123_DATE_TIME.format(now.plusSeconds(seconds).atZone(ZoneOffset.UTC))
            assertEquals(NetworkResult.Failure(NetworkFailure.RATE_LIMITED, now.plusSeconds(seconds.coerceAtLeast(1))), classifier.classify(response(429, header), now))
        }
    }

    @Test fun malformedAndOverflowingRetryAfterFallBackToSixtySeconds() {
        for (header in listOf("invalid", "-5", Long.MAX_VALUE.toString())) {
            assertEquals(NetworkResult.Failure(NetworkFailure.RATE_LIMITED, now.plusSeconds(60)), DefaultHttpErrorClassifier().classify(response(429, header), now))
        }
    }

    @Test fun customClassificationStillUsesSharedGateWithoutRetrying() = runTest {
        var requests = 0
        var classifications = 0
        val expected = NetworkResult.Failure(NetworkFailure.RATE_LIMITED, now.plusSeconds(90))
        val executor = RequestExecutor(Clock.fixed(now, ZoneOffset.UTC), errorClassifier = HttpErrorClassifier { response, instant ->
            assertEquals(418, response.code())
            assertEquals(now, instant)
            classifications++
            expected
        })
        repeat(2) {
            assertEquals(expected, executor.execute { requests++; response(418, "90") })
        }
        assertEquals(1, requests)
        assertEquals(1, classifications)
    }
}
