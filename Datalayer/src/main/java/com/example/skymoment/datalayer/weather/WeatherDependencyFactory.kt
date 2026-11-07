package com.example.skymoment.datalayer.weather

import com.example.skymoment.datalayer.network.DefaultHttpErrorClassifier
import com.example.skymoment.datalayer.network.NetworkFactory
import com.example.skymoment.datalayer.network.RequestExecutor
import com.example.skymoment.domain.DEVICE_LOCATION_PROVIDER
import com.example.skymoment.domain.Location
import com.example.skymoment.domain.LocationRepository
import com.example.skymoment.domain.LocationSessionRepository
import com.example.skymoment.domain.SearchHistoryRepository
import com.example.skymoment.domain.WeatherDependencies
import com.example.skymoment.domain.WeatherRepository
import java.time.Clock

/**
 * 数据层装配后的依赖快照，仅向上暴露领域接口。
 * 以统一依赖接口隐藏具体数据层实现。
 */
private class DefaultWeatherDependencies(
    // 向功能层公开地点仓储契约。
    override val locations: LocationRepository,
    // 向功能层公开天气仓储契约。
    override val weather: WeatherRepository,
    // 向功能层公开当前会话契约。
    override val sessions: LocationSessionRepository,
    // 向功能层公开历史词仓储契约。
    override val searchHistory: SearchHistoryRepository,
) : WeatherDependencies

/**
 * 数据层封装供应商、网络及仓储组装，对上只暴露领域契约。
 */
object WeatherDependencyFactory {
    /*
     * 组装共享连接池、执行器、映射器和仓储，对上仅暴露领域依赖契约。
     * 返回领域依赖集合，避免功能层引用 Retrofit 类型。
     */
    fun create(
        // 接受由宿主提供的本地搜索历史存储。
        searchHistory: SearchHistoryRepository,
        // 统一注入时钟，供时效判断与测试控制。
        clock: Clock = Clock.systemUTC(),
        // 允许宿主注入系统逆地理编码，缺省不补充名称。
        deviceLocationNames: suspend (Location, String) -> Location? = { _, _ -> null },
    ): WeatherDependencies {
        // 同一组仓储共享连接池与限流门，供应商细节不进入 app 或 feature。
        val client = NetworkFactory.client()
        // 创建包含供应商限额策略的共享执行器。
        val executor = requestExecutor(clock)
        // 让所有仓储使用相同的时钟与字段校验规则。
        val mapper = OpenMeteoResponseMapper(clock)
        val geocoding = NetworkFactory.retrofit(OpenMeteoEndpoints.GEOCODING, client)
            .create(OpenMeteoGeocodingService::class.java)
        val locationNames = ProviderLocationNameRepository(
            mapOf(
                DEVICE_LOCATION_PROVIDER to DeviceLocationNameRepository(deviceLocationNames),
                OPEN_METEO_LOCATION_PROVIDER to OpenMeteoLocationNameRepository(
                    geocoding,
                    executor,
                    mapper
                ),
            )
        )
        // 组合并返回本次应用实例所需的数据依赖。
        return DefaultWeatherDependencies(
            // 创建地点搜索及名称更新仓储。
            OpenMeteoLocationRepository(
                // 将公共地理主机绑定到地理编码服务声明。
                geocoding,
                // 复用执行器和映射器，并传入设备名称解析能力。
                executor, mapper, locationNames = locationNames
            ),
            // 创建当前、小时和逐日天气仓储。
            OpenMeteoWeatherRepository(
                // 将公共天气主机绑定到预报服务声明。
                NetworkFactory.retrofit(OpenMeteoEndpoints.FORECAST, client)
                    .create(OpenMeteoForecastService::class.java),
                // 天气与地点请求共享错误处理和数据映射基础。
                executor, mapper
            ),
            // 为本次依赖集合创建进程内会话仓储。
            InMemoryLocationSessionRepository(),
            // 复用宿主传入的搜索词历史实现。
            searchHistory,
        )
    }

    /*
     * 注入供应商配额识别策略，基础网络层仍保持协议无关。
     */
    internal fun requestExecutor(clock: Clock) =
        // 把日月配额识别注入通用执行器，保持网络层无供应商耦合。
        RequestExecutor(
            clock,
            errorClassifier = DefaultHttpErrorClassifier(OpenMeteoErrors::quotaExceeded)
        )
}
