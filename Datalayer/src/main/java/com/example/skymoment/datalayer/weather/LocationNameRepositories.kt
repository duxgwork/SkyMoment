package com.example.skymoment.datalayer.weather

import com.example.skymoment.datalayer.network.NetworkResult
import com.example.skymoment.datalayer.network.RequestExecutor
import com.example.skymoment.domain.DataResult
import com.example.skymoment.domain.Location
import com.example.skymoment.domain.LocationNameRepository
import com.example.skymoment.domain.WeatherFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.milliseconds

internal const val OPEN_METEO_LOCATION_PROVIDER = "open-meteo"

/** 按来源选择名称策略；新增来源只需在装配处注册，不改变分发流程。 */
class ProviderLocationNameRepository(strategies: Map<String, LocationNameRepository>) :
    LocationNameRepository {
    private val strategies = strategies.toMap()

    override suspend fun refreshLocationName(
        location: Location,
        language: String
    ): DataResult<Location> =
        strategies[location.provider]?.refreshLocationName(location, language)
            ?: DataResult.Failure(WeatherFailure.UNSUPPORTED_LOCATION)
}

/** 适配系统逆地理编码；名称不可用时保留原地点，外部取消仍向上传播。 */
class DeviceLocationNameRepository(
    private val resolveName: suspend (Location, String) -> Location?,
) : LocationNameRepository {
    override suspend fun refreshLocationName(
        location: Location,
        language: String
    ): DataResult<Location> {
        val resolved = try {
            withTimeoutOrNull(4_000.milliseconds) { resolveName(location, language) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        return DataResult.Success(resolved?.let { location.withResolvedName(it) } ?: location)
    }
}

/** 按 GeoNames ID 查询本地化名称，供应商返回的其他字段不能改写已有天气上下文。 */
class OpenMeteoLocationNameRepository(
    private val service: OpenMeteoGeocodingService,
    private val executor: RequestExecutor,
    private val mapper: OpenMeteoResponseMapper,
) : LocationNameRepository {
    override suspend fun refreshLocationName(
        location: Location,
        language: String
    ): DataResult<Location> =
        when (val result = executor.execute {
            service.get(location.id, if (language.startsWith("zh")) "zh" else "en")
        }) {
            is NetworkResult.Failure -> result.toDomain()
            is NetworkResult.Success -> mapper.mapLocation(result.body)
                ?.let { location.withResolvedName(it) }
                ?.let { DataResult.Success(it) }
                ?: DataResult.Failure(WeatherFailure.DATA_INVALID)
        }
}

/** 所有名称策略共用身份校验；只接受同来源同 ID 的非空名称。 */
private fun Location.withResolvedName(resolved: Location): Location? =
    resolved.takeIf { it.provider == provider && it.id == id && it.name.isNotBlank() }
        ?.let { copy(name = it.name, administrativePath = it.administrativePath) }
