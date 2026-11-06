package com.example.skymoment.common

import com.example.skymoment.domain.WeatherFailure
import com.example.skymoment.framework.LocaleContext
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WeatherResourcesTest {
    @Test fun everyFailureAndKnownWeatherCodeHasTextInEachSupportedLocale() {
        val codes = listOf(0,1,2,3,45,48,51,53,55,56,57,61,63,65,66,67,71,73,75,77,80,81,82,85,86,95,96,99)
        listOf("en", "zh-Hans", "zh-Hant").forEach { tag ->
            val context = LocaleContext.wrap(RuntimeEnvironment.getApplication(), listOf(Locale.forLanguageTag(tag)))
            WeatherFailure.entries.forEach { assertTrue(context.getString(it.messageRes()).isNotBlank()) }
            codes.forEach {
                assertNotEquals(R.string.weather_unknown, WeatherPresentation.description(it))
                assertTrue(context.getString(WeatherPresentation.description(it)).isNotBlank())
            }
        }
    }

    @Test fun failureCategoriesKeepActionableMessages() {
        assertEquals(R.string.network_error, WeatherFailure.NETWORK.messageRes())
        assertEquals(R.string.timeout_error, WeatherFailure.TIMEOUT.messageRes())
        assertEquals(R.string.rate_error, WeatherFailure.RATE_LIMITED.messageRes())
        assertEquals(R.string.quota_error, WeatherFailure.QUOTA.messageRes())
        assertEquals(R.string.invalid_error, WeatherFailure.DATA_INVALID.messageRes())
        assertEquals(R.string.configuration_error, WeatherFailure.AUTHENTICATION.messageRes())
        assertEquals(R.string.configuration_error, WeatherFailure.REQUEST_REJECTED.messageRes())
        assertEquals(R.string.request_error, WeatherFailure.SERVICE_UNAVAILABLE.messageRes())
        assertEquals(R.string.request_error, WeatherFailure.UNSUPPORTED_LOCATION.messageRes())
    }
}
