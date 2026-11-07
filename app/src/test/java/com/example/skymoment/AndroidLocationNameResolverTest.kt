package com.example.skymoment

import android.app.Application
import android.location.Address
import android.location.Geocoder
import com.example.skymoment.test.WeatherFixtures
import java.util.Locale
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

/** 用可控系统回调覆盖名称提取，不修改真实 Geocoder 或调用外部服务。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, shadows = [NameGeocoder::class])
class AndroidLocationNameResolverTest {
    @Before fun reset() { NameGeocoder.addresses = emptyList(); NameGeocoder.fail = false; NameGeocoder.present = true }

    @Test fun extractsOnlyDistinctAdministrativeNamesAndPreservesLocationIdentity() = runTest {
        NameGeocoder.addresses = listOf(Address(Locale.ENGLISH).apply {
            subLocality = " District "; locality = "District"; adminArea = "City"; countryName = "Country"
            thoroughfare = "Private street"; featureName = "Private building"
        })
        val location = WeatherFixtures.location()
        val resolved = AndroidLocationNameResolver(RuntimeEnvironment.getApplication()).resolveName(location, "en")
        assertEquals(location.copy(name = "District", administrativePath = listOf("City", "Country")), resolved)
    }

    @Test fun absentServiceEmptyAddressAndErrorsReturnNoReplacement() = runTest {
        val resolver = AndroidLocationNameResolver(RuntimeEnvironment.getApplication())
        val location = WeatherFixtures.location()
        NameGeocoder.present = false
        assertNull(resolver.resolveName(location, "en"))
        NameGeocoder.present = true
        assertNull(resolver.resolveName(location, "en"))
        NameGeocoder.addresses = listOf(Address(Locale.ENGLISH).apply { thoroughfare = "Street only" })
        assertNull(resolver.resolveName(location, "en"))
        NameGeocoder.fail = true
        assertNull(resolver.resolveName(location, "en"))
    }
}

@Implements(Geocoder::class)
class NameGeocoder {
    companion object {
        var addresses: List<Address> = emptyList()
        var fail = false
        var present = true
        @JvmStatic @Implementation fun isPresent(): Boolean = present
    }
    @Implementation(minSdk = 33)
    fun getFromLocation(latitude: Double, longitude: Double, maxResults: Int, listener: Geocoder.GeocodeListener) {
        if (fail) listener.onError("unavailable") else listener.onGeocode(addresses.take(maxResults))
    }
}
