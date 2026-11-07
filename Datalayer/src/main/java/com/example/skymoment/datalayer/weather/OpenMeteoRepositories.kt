package com.example.skymoment.datalayer.weather

import com.example.skymoment.datalayer.network.NetworkFailure
import com.example.skymoment.datalayer.network.NetworkResult
import com.example.skymoment.datalayer.network.RequestExecutor
import com.example.skymoment.domain.Coordinates
import com.example.skymoment.domain.CurrentWeather
import com.example.skymoment.domain.DEVICE_LOCATION_PROVIDER
import com.example.skymoment.domain.DataResult
import com.example.skymoment.domain.Forecast
import com.example.skymoment.domain.HourlyForecast
import com.example.skymoment.domain.LocatedWeather
import com.example.skymoment.domain.Location
import com.example.skymoment.domain.LocationNameRepository
import com.example.skymoment.domain.LocationRepository
import com.example.skymoment.domain.WeatherFailure
import com.example.skymoment.domain.WeatherRepository
import com.example.skymoment.domain.map
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.ZoneId
import kotlin.time.Duration.Companion.milliseconds

/**
 * 组合地理编码、中文变体查询和名称更新，对上返回领域地点。
 * 通过领域接口隔离上层与供应商实现。
 */
class OpenMeteoLocationRepository(
    // 注入地点搜索与 ID 查询的传输服务。
    private val service: OpenMeteoGeocodingService,
    // 复用带超时、重试和限流能力的请求执行器。
    private val executor: RequestExecutor,
    // 注入地点字段校验及领域模型转换器。
    private val mapper: OpenMeteoResponseMapper,
    // 注入中文原词、去后缀与拼音查询生成规则。
    private val queries: LocationQueryVariants = LocationQueryVariants(),
    // 把拼音转换等计算移到指定计算调度器。
    private val queryDispatcher: CoroutineDispatcher = Dispatchers.Default,
    // 名称更新由可替换策略处理，搜索流程无需了解设备或其他来源。
    private val locationNames: LocationNameRepository =
        ProviderLocationNameRepository(
            mapOf(
                OPEN_METEO_LOCATION_PROVIDER to OpenMeteoLocationNameRepository(
                    service,
                    executor,
                    mapper
                )
            )
        ),
) : LocationRepository {
    /*
     * 在同一总体时限内查询原词和别名，按地点标识合并成功结果，部分失败不抹掉已找到的地点。
     */
    override suspend fun searchLocations(
        query: String,
        language: String
    ): DataResult<List<Location>> =
        // 所有查询变体共用四十五秒整体预算。
        withTimeoutOrNull(45_000.milliseconds) {
            // 在计算调度器上生成去重后的查询变体。
            val variants = withContext(queryDispatcher) { queries.generateVariants(query) }
            // 没有有效查询时直接返回空结果，避免网络调用。
            if (variants.isEmpty()) return@withTimeoutOrNull DataResult.Empty
            // 使用结构化并发管理所有查询子任务。
            val responses = coroutineScope {
                // 为每个原词或别名创建独立请求任务。
                variants.map { term ->
                    // 并发执行各变体，缩短完整搜索等待时间。
                    async {
                        // 让每个请求遵守共享限流与最多一次重试。
                        executor.execute {
                            // 调用地理编码搜索端点。
                            service.search(
                                // 传入当前这一条查询变体。
                                term,
                                // 将界面语言归一为供应商支持的语言值。
                                geoLanguage(language)
                            )
                        }
                    }
                    // 等待全部变体完成后统一合并，保留变体顺序。
                }.awaitAll()
            }
            // 收集所有有效候选，稍后按 ID 去重。
            val results = mutableListOf<Location>()
            // 记录失败原因，供完全没有候选时返回。
            var failure: DataResult.Failure? = null
            // 逐个检查每个变体的最终网络结果。
            responses.forEach { response ->
                // 按成功或失败分别处理响应。
                when (response) {
                    // 无其他已记录失败时保存网络错误的领域映射。
                    is NetworkResult.Failure -> if (failure == null) failure = response.toDomain()
                    // 成功传输后仍需检查业务响应结构。
                    is NetworkResult.Success -> {
                        // 取出已解析的搜索响应对象。
                        val body = response.body
                        // 错误标记或缺少合法空响应标识时视为坏数据。
                        if (body.error || (body.results == null && body.generationTime == null)) {
                            // 记录格式错误而非伪装成没有搜索结果。
                            failure = DataResult.Failure(WeatherFailure.DATA_INVALID)
                        } else {
                            // 缺失列表视为空，单项无效地点在映射时过滤。
                            val mapped = body.results.orEmpty().mapNotNull(mapper::mapLocation)
                            // 非空原列表全部被过滤说明响应没有有效地点。
                            if (body.results?.isNotEmpty() == true && mapped.isEmpty()) {
                                // 将全坏候选归为数据错误，帮助界面区分故障与无匹配。
                                failure = DataResult.Failure(WeatherFailure.DATA_INVALID)
                            }
                            // 把有效候选加入跨变体结果集合。
                            results += mapped
                        }
                    }
                }
            }
            // 按是否找到候选、是否发生错误决定最终结果。
            when {
                // 只要存在有效候选就返回成功，并按地点 ID 去重。
                results.isNotEmpty() -> DataResult.Success(results.distinctBy { it.id })
                // 没有候选但存在故障时返回已记录的错误。
                failure != null -> failure
                // 所有变体合法且均无结果时返回空结果。
                else -> DataResult.Empty
            }
            // 整体查询预算耗尽时报告超时。
        } ?: DataResult.Failure(WeatherFailure.TIMEOUT)

    override suspend fun refreshLocationName(
        location: Location,
        language: String
    ): DataResult<Location> =
        locationNames.refreshLocationName(location, language)

    /*
     * 把有效语言标签归一为供应商支持的中文或英文查询参数。
     */
    private fun geoLanguage(value: String): String = if (value.startsWith("zh")) "zh" else "en"
}

/**
 * 封装各天气端点并执行数据映射，保持区域查询独立。
 * 以领域天气接口向功能组件提供数据。
 */
class OpenMeteoWeatherRepository(
    // 注入当前、小时与逐日天气传输服务。
    private val service: OpenMeteoForecastService,
    // 复用与地理编码一致的请求执行策略。
    private val executor: RequestExecutor,
    // 复用天气字段和时间窗口的映射器。
    private val mapper: OpenMeteoResponseMapper,
) : WeatherRepository {
    /*
     * 请求地点时区内的小时预报，并将传输结果映射为领域结果。
     */
    override suspend fun getHourlyForecast(
        location: Location,
        language: String
    ): DataResult<HourlyForecast> =
        // 先通过执行器获取小时预报的传输结果。
        when (val result = executor.execute {
            // 将地点坐标和明确时区传给小时端点。
            service.hourly(location.latitude, location.longitude, location.timeZone.id)
        }) {
            // 成功响应经单位和时间窗口校验后转换为小时领域模型。
            is NetworkResult.Success -> mapper.mapHourlyForecast(result.body, location)
            // 网络失败保持错误类别和恢复时间。
            is NetworkResult.Failure -> result.toDomain()
        }

    /*
     * 使用设备坐标和 auto 时区请求天气，成功后构造可供首页复用的地点与天气。
     * 同时返回坐标地点及首屏当前天气。
     */
    override suspend fun getWeatherAt(
        // 接收已通过合法性校验的设备坐标。
        coordinates: Coordinates,
        // 保留统一语言参数，天气 HTTP 本身不发送语言。
        language: String
    ): DataResult<LocatedWeather> =
        // 使用统一执行器处理设备坐标天气请求。
        when (val result = executor.execute {
            // 首次请求让供应商根据坐标解析实际时区。
            service.current(coordinates.latitude, coordinates.longitude, "auto")
        }) {
            // 网络失败不创建可用地点会话。
            is NetworkResult.Failure -> result.toDomain()
            // 成功传输后从天气响应建立设备地点。
            is NetworkResult.Success -> {
                // 安全解析服务端返回的地点时区。
                val zone = runCatching { ZoneId.of(result.body.timezone) }.getOrNull()
                // 时区非法时终止首屏数据构造。
                if (zone == null) DataResult.Failure(WeatherFailure.DATA_INVALID)
                else {
                    // 使用返回的地点时区，而非设备时区；名称由独立的逆地理编码请求补齐。
                    val location = Location(
                        // 设备地点使用固定来源和身份，名称暂留空供系统补齐。
                        DEVICE_LOCATION_PROVIDER, "current", "", emptyList(),
                        // 保留原设备坐标及服务端解析的时区。
                        coordinates.latitude, coordinates.longitude, zone
                    )
                    // 仅在当前天气映射成功时组合地点与天气返回。
                    mapper.mapCurrentWeather(result.body, location)
                        .map { LocatedWeather(location, it) }
                }
            }
        }

    /*
     * 使用已选地点的坐标和时区查询当前天气。
     */
    override suspend fun getCurrentWeather(
        location: Location,
        language: String
    ): DataResult<CurrentWeather> =
        // 执行已有地点的当前天气查询。
        when (val result = executor.execute {
            // 已有地点继续沿用保存的坐标和时区。
            service.current(location.latitude, location.longitude, location.timeZone.id)
        }) {
            // 校验当前指标后返回当前天气领域结果。
            is NetworkResult.Success -> mapper.mapCurrentWeather(result.body, location)
            // 失败结果经统一映射交给页面处理。
            is NetworkResult.Failure -> result.toDomain()
        }

    /*
     * 请求地点七日预报，保持小时与当前天气请求互相独立。
     */
    override suspend fun getDailyForecast(
        location: Location,
        language: String
    ): DataResult<Forecast> =
        // 通过执行器独立获取七日预报。
        when (val result = executor.execute {
            // 把所选地点坐标和时区传入日预报请求。
            service.daily(location.latitude, location.longitude, location.timeZone.id)
        }) {
            // 成功响应转换为当地七日窗口内的数据。
            is NetworkResult.Success -> mapper.mapDailyForecast(result.body, location)
            // 日预报失败不影响其他区域的传输结果。
            is NetworkResult.Failure -> result.toDomain()
        }
}

/*
 * 将通用网络错误映射为天气领域错误，同时保留限流恢复时间。
 */
internal fun NetworkResult.Failure.toDomain() = DataResult.Failure(
    // 将网络层错误类型转换为业务层可理解的分类。
    when (kind) {
        // 保留连接或 DNS 等网络故障语义。
        NetworkFailure.NETWORK -> WeatherFailure.NETWORK
        // 保留请求超时语义。
        NetworkFailure.TIMEOUT -> WeatherFailure.TIMEOUT
        // 保留鉴权或访问权限失败语义。
        NetworkFailure.AUTHENTICATION -> WeatherFailure.AUTHENTICATION
        // 保留长期配额耗尽语义。
        NetworkFailure.QUOTA -> WeatherFailure.QUOTA
        // 保留短期限流语义。
        NetworkFailure.RATE_LIMITED -> WeatherFailure.RATE_LIMITED
        // 把服务端故障表达为服务暂不可用。
        NetworkFailure.SERVER -> WeatherFailure.SERVICE_UNAVAILABLE
        // 把传输解析异常表达为业务数据无效。
        NetworkFailure.INVALID_DATA -> WeatherFailure.DATA_INVALID
        // 把其余失败状态表达为请求被拒绝。
        NetworkFailure.REJECTED -> WeatherFailure.REQUEST_REJECTED
        // 传递原限流截止时刻，避免丢失重试约束。
    }, retryAt
)
