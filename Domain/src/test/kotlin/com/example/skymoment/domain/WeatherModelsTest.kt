package com.example.skymoment.domain

import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class WeatherModelsTest {
    @Test fun acceptsCoordinateBoundariesAndRejectsNonFiniteOrOutOfRangeValues() {
        Coordinates(-90.0, -180.0)
        Coordinates(90.0, 180.0)
        Coordinates(0.0, 0.0)
        listOf(-90.01, 90.01, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY).forEach {
            assertThrows(IllegalArgumentException::class.java) { Coordinates(it, 0.0) }
        }
        listOf(-180.01, 180.01, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY).forEach {
            assertThrows(IllegalArgumentException::class.java) { Coordinates(0.0, it) }
        }
    }

    @Test fun mapTransformsOnlySuccessAndPreservesFailureMetadata() {
        var calls = 0
        val transform: (Int) -> String = { calls++; "value=$it" }
        assertEquals(DataResult.Success("value=0"), DataResult.Success(0).map(transform))
        assertSame(DataResult.Empty, DataResult.Empty.map(transform))
        val failure = DataResult.Failure(WeatherFailure.RATE_LIMITED, Instant.EPOCH)
        assertSame(failure, failure.map(transform))
        assertEquals(1, calls)
    }
}
