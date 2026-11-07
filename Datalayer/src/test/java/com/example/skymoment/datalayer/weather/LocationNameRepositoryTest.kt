package com.example.skymoment.datalayer.weather

import com.example.skymoment.datalayer.network.RequestExecutor
import com.example.skymoment.domain.DEVICE_LOCATION_PROVIDER
import com.example.skymoment.domain.DataResult
import com.example.skymoment.domain.LocationNameRepository
import com.example.skymoment.domain.WeatherFailure
import com.example.skymoment.test.WeatherFixtures
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Response
import java.time.Clock
import java.time.ZoneId

/** 验证可替换的名称策略保持身份、坐标和取消契约。 */
@OptIn(ExperimentalCoroutinesApi::class)
class LocationNameRepositoryTest {
    private val place = WeatherFixtures.location().copy(provider = DEVICE_LOCATION_PROVIDER)

    @Test fun newProviderCanBeRegisteredWithoutChangingExistingStrategies() = runTest {
        var calls = 0
        val strategies = mutableMapOf("custom" to LocationNameRepository { location, language ->
            calls++
            assertEquals("zh-Hant", language)
            DataResult.Success(location.copy(name = "新名稱"))
        })
        val repository = ProviderLocationNameRepository(strategies)
        strategies.clear()
        val custom = place.copy(provider = "custom")
        assertEquals(DataResult.Success(custom.copy(name = "新名稱")), repository.refreshLocationName(custom, "zh-Hant"))
        assertEquals(DataResult.Failure(WeatherFailure.UNSUPPORTED_LOCATION), repository.refreshLocationName(place, "en"))
        assertEquals(1, calls)
    }

    @Test fun deviceNameUpdatesOnlyDisplayFieldsAndRejectsUnrelatedResults() = runTest {
        val renamed = place.copy(name = "新名称", administrativePath = listOf("新区"))
        val repository = DeviceLocationNameRepository { _, _ ->
            renamed.copy(latitude = 0.0, longitude = 0.0, timeZone = ZoneId.of("UTC"))
        }
        assertEquals(DataResult.Success(renamed), repository.refreshLocationName(place, "en"))
        for (invalid in listOf(place.copy(id = "other"), place.copy(provider = "other"), place.copy(name = " "))) {
            assertEquals(DataResult.Success(place), DeviceLocationNameRepository { _, _ -> invalid }.refreshLocationName(place, "en"))
        }
    }

    @Test fun cancellationReleasesDeviceResolverInsteadOfReturningFallback() = runTest {
        var released = false
        var returned = false
        val repository = DeviceLocationNameRepository { _, _ ->
            try { awaitCancellation() } finally { released = true }
        }
        val job = launch {
            repository.refreshLocationName(place, "en")
            returned = true
        }
        runCurrent()
        job.cancelAndJoin()
        assertTrue(released)
        assertFalse(returned)
    }

    @Test fun providerNameCannotReplaceCoordinatesOrTimezoneAndRejectsWrongIdentity() = runTest {
        var response = OpenMeteoLocationDto(1, "新名称", 10.0, 20.0, "UTC", "Country", "CC")
        val service = object : OpenMeteoGeocodingService {
            override suspend fun search(query: String, language: String, count: Int, format: String): Response<OpenMeteoGeocodingResponseDto> = error("Name refresh must not search")
            override suspend fun get(id: String, language: String): Response<OpenMeteoLocationDto> {
                assertEquals("1", id)
                assertEquals("zh", language)
                return Response.success(response)
            }
        }
        val repository = OpenMeteoLocationNameRepository(service, RequestExecutor(), OpenMeteoResponseMapper(Clock.systemUTC()))
        val original = place.copy(provider = OPEN_METEO_LOCATION_PROVIDER, id = "1")
        val updated = (repository.refreshLocationName(original, "zh-Hans") as DataResult.Success).data
        assertEquals(original.copy(name = updated.name, administrativePath = updated.administrativePath), updated)
        assertEquals("新名称", updated.name)
        response = response.copy(id = 2)
        assertEquals(DataResult.Failure(WeatherFailure.DATA_INVALID), repository.refreshLocationName(original, "zh"))
    }
}
