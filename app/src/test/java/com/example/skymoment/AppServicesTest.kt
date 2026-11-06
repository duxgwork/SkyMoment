package com.example.skymoment

import android.app.Application
import com.example.skymoment.test.WeatherFixtures
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AppServicesTest {
    @Test fun servicesReuseDependenciesAndSessionsWithinTheApplication() {
        val service = AppServices().apply { init(RuntimeEnvironment.getApplication()) }
        assertSame(service.deviceLocation, service.deviceLocation)
        assertSame(service.locations, service.locations)
        assertSame(service.weather, service.weather)
        assertSame(service.sessions, service.sessions)
        assertSame(service.searchHistory, service.searchHistory)
        val location = WeatherFixtures.location()
        val token = service.sessions.select(location, WeatherFixtures.current())
        assertEquals(location, service.sessions.resolve(token))
        assertEquals(WeatherFixtures.current(), service.sessions.initialWeather(token))
        service.sessions.clear()
        assertNull(service.sessions.resolve(token))
    }
}
