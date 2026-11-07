package com.example.skymoment

import android.app.Application
import com.example.skymoment.domain.HomeDependencies
import com.example.skymoment.domain.SearchDependencies
import com.example.skymoment.domain.WeatherDisplayDependencies
import com.example.skymoment.test.WeatherFixtures
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ApplicationServiceProviderTest {
    @Test fun servicesReuseDependenciesAndSessionsWithinTheApplication() {
        val service = ApplicationServiceProvider().apply { init(RuntimeEnvironment.getApplication()) }
        assertSame(service.deviceLocation, service.deviceLocation)
        assertSame(service.locations, service.locations)
        assertSame(service.weather, service.weather)
        assertSame(service.sessions, service.sessions)
        assertSame(service.searchHistory, service.searchHistory)
        val home: HomeDependencies = service
        val search: SearchDependencies = service
        val weather: WeatherDisplayDependencies = service
        assertSame(home.sessions, search.sessions)
        assertSame(search.sessions, weather.sessions)
        assertSame(home.weather, weather.weather)
        assertSame(search.locations, weather.locations)
        val location = WeatherFixtures.location()
        val token = service.sessions.createSession(location, WeatherFixtures.current())
        assertEquals(location, service.sessions.findLocation(token))
        assertEquals(WeatherFixtures.current(), service.sessions.findInitialWeather(token))
        service.sessions.clear()
        assertNull(service.sessions.findLocation(token))
    }
}
