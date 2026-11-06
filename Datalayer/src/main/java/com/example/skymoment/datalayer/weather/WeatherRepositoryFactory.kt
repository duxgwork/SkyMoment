package com.example.skymoment.datalayer.weather

import com.example.skymoment.domain.Location
import com.example.skymoment.domain.LocationRepository
import com.example.skymoment.domain.LocationSessionRepository
import com.example.skymoment.domain.SearchHistoryRepository
import com.example.skymoment.domain.WeatherDependencies
import com.example.skymoment.domain.WeatherRepository
import com.example.skymoment.datalayer.network.RequestExecutor
import com.example.skymoment.datalayer.network.NetworkFactory
import java.time.Clock

/** 数据层装配后的依赖快照，仅向上暴露领域接口。 */
private class DefaultWeatherDependencies(
    override val locations: LocationRepository,
    override val weather: WeatherRepository,
    override val sessions: LocationSessionRepository,
    override val searchHistory: SearchHistoryRepository,
) : WeatherDependencies

/** 数据层封装供应商、网络及仓储组装，对上只暴露领域契约。 */
object WeatherRepositoryFactory {
    // 组装共享连接池、执行器、映射器和仓储，对上仅暴露领域依赖契约。
    fun create(
        searchHistory: SearchHistoryRepository,
        clock: Clock = Clock.systemUTC(),
        deviceLocationNames: suspend (Location, String) -> Location? = { _, _ -> null },
    ): WeatherDependencies {
        // 同一组仓储共享连接池与限流门，供应商细节不进入 app 或 feature。
        val client = NetworkFactory.client()
        val executor = requestExecutor(clock)
        val mapper = OpenMeteoMapper(clock)
        return DefaultWeatherDependencies(
            OpenMeteoLocationRepository(
                NetworkFactory.retrofit(OpenMeteoEndpoints.GEOCODING, client).create(OpenMeteoGeocodingService::class.java),
                executor, mapper, deviceLocationNames = deviceLocationNames
            ),
            OpenMeteoWeatherRepository(
                NetworkFactory.retrofit(OpenMeteoEndpoints.FORECAST, client).create(OpenMeteoForecastService::class.java),
                executor, mapper
            ),
            InMemoryLocationSessionRepository(),
            searchHistory,
        )
    }

    // 注入供应商配额识别策略，基础网络层仍保持协议无关。
    internal fun requestExecutor(clock: Clock) =
        RequestExecutor(clock, quotaExceeded = OpenMeteoErrors::quotaExceeded)
}
