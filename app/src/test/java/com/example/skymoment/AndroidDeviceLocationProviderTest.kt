package com.example.skymoment

import android.Manifest
import android.app.Application
import android.location.Location
import android.location.LocationManager
import android.os.Looper
import android.os.SystemClock
import com.example.skymoment.domain.Coordinates
import com.example.skymoment.test.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AndroidDeviceLocationProviderTest {
    @get:Rule val main = MainDispatcherRule()
    private val app get() = RuntimeEnvironment.getApplication()
    private val manager get() = app.getSystemService(LocationManager::class.java)

    @Test fun permissionTracksCoarseFineAndRevocation() {
        val provider = AndroidDeviceLocationProvider(app)
        shadowOf(app).denyPermissions(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
        assertFalse(provider.hasPermission())
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
        assertTrue(provider.hasPermission())
        shadowOf(app).denyPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        assertTrue(provider.hasPermission())
        shadowOf(app).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        assertFalse(provider.hasPermission())
    }

    @Test fun missingPermissionOrDisabledLocationReturnsNoFix() = runTest(main.dispatcher) {
        shadowOf(app).denyPermissions(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
        assertNull(AndroidDeviceLocationProvider(app).locate())
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
        shadowOf(manager).setLocationEnabled(false)
        assertNull(AndroidDeviceLocationProvider(app).locate())
    }

    @Test fun approximatePermissionDoesNotRequestGpsOnlyProvider() = runTest(main.dispatcher) {
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
        shadowOf(app).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        shadowOf(manager).setProviderEnabled(LocationManager.NETWORK_PROVIDER, false)
        shadowOf(manager).setProviderEnabled(LocationManager.FUSED_PROVIDER, false)
        shadowOf(manager).setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        assertNull(AndroidDeviceLocationProvider(app).locate())
    }

    @Test fun recentNetworkFixReturnsCoordinatesAndCancellationReleasesRequests() = runTest(main.dispatcher) {
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
        shadowOf(manager).setProviderEnabled(LocationManager.NETWORK_PROVIDER, true)
        shadowOf(manager).setProviderEnabled(LocationManager.FUSED_PROVIDER, false)
        val result = async { AndroidDeviceLocationProvider(app).locate() }
        runCurrent()
        shadowOf(manager).simulateLocation(Location(LocationManager.NETWORK_PROVIDER).apply {
            latitude = 31.2; longitude = 121.5
            time = System.currentTimeMillis()
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        })
        shadowOf(Looper.getMainLooper()).idle()
        runCurrent()
        assertEquals(Coordinates(31.2, 121.5), result.await())
        assertTrue(shadowOf(manager).getLocationRequests(LocationManager.NETWORK_PROVIDER).isEmpty())
        shadowOf(manager).setLastKnownLocation(LocationManager.NETWORK_PROVIDER, null)
        val pending = async { AndroidDeviceLocationProvider(app).locate() }
        runCurrent()
        assertFalse(pending.isCompleted)
        pending.cancel(); runCurrent()
        assertTrue(shadowOf(manager).getLocationRequests(LocationManager.NETWORK_PROVIDER).isEmpty())
    }
}
