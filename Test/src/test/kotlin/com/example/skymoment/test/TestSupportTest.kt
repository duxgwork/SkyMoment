package com.example.skymoment.test

import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TestSupportTest {
    @get:Rule val main = MainDispatcherRule()

    @Test fun mainDispatcherUsesTheSameVirtualScheduler() = runTest(main.dispatcher) {
        var invoked = false
        launch(Dispatchers.Main) { invoked = true }
        assertFalse(invoked)
        runCurrent()
        assertTrue(invoked)
    }

    @Test fun zonedClocksKeepAdvancingOnTheSharedTimeline() = runTest(main.dispatcher) {
        val clock = SchedulerClock(testScheduler, Instant.EPOCH)
        val zone = ZoneId.of("America/New_York")
        val zoned = clock.withZone(zone)
        advanceTimeBy(3600)
        assertEquals(Instant.EPOCH.plusMillis(3600), zoned.instant())
        assertEquals(clock.instant(), zoned.instant())
        assertEquals(zone, zoned.zone)
    }

    @Test fun selectingAndClearingInvalidatePreviousTokensAndWeather() {
        val sessions = FakeLocationSessionRepository()
        val place = WeatherFixtures.location()
        val weather = WeatherFixtures.current()
        val first = sessions.createSession(place, weather)
        assertEquals(weather, sessions.findInitialWeather(first))
        val next = sessions.createSession(place.copy(id = "2"))
        assertNotEquals(first, next)
        assertNull(sessions.findLocation(first))
        assertNull(sessions.findInitialWeather(first))
        assertEquals("2", sessions.findLocation(next)?.id)
        assertNull(sessions.findInitialWeather(next))
        sessions.clear()
        assertNull(sessions.findLocation(next))
        assertNull(FakeLocationSessionRepository().findLocation(next))
    }
}
