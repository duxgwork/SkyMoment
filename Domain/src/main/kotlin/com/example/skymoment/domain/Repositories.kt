package com.example.skymoment.domain

/** language 使用统一解析后的 zh / en；取消由实现继续抛出。 */
interface LocationRepository {
    // 按关键词与语言返回候选地点，不自动替用户选择同名结果。
    suspend fun search(query: String, language: String): DataResult<List<Location>>
    // 按语言刷新地点名称，默认保留原地点。
    suspend fun refresh(location: Location, language: String): DataResult<Location> =
        DataResult.Success(location)
}

/** 当前、逐日、逐小时和设备坐标天气的领域读取契约。 */
interface WeatherRepository {
    // 按设备坐标返回带实际地点时区的当前天气。
    suspend fun currentAt(coordinates: Coordinates, language: String): DataResult<LocatedWeather> =
        DataResult.Failure(WeatherFailure.UNSUPPORTED_LOCATION)

    // 读取已选地点的当前天气。
    suspend fun current(location: Location, language: String): DataResult<CurrentWeather>
    // 读取已选地点的七日预报。
    suspend fun forecast(location: Location, language: String): DataResult<Forecast>
    // 读取已选地点的逐小时预报。
    suspend fun hourly(location: Location, language: String): DataResult<HourlyForecast>
}

/** 仅在进程内保留当前选择，新的选择使之前的 token 失效。 */
interface LocationSessionRepository {
    // 创建新的随机会话并使此前选择失效，可附带首屏天气供复用。
    fun select(location: Location, initialWeather: CurrentWeather? = null): String
    // 仅解析当前有效的内存会话，失效 token 返回 null。
    fun resolve(token: String): Location?
    // 按有效会话读取启动天气，供天气页复用并减少重复请求。
    fun initialWeather(token: String): CurrentWeather? = null
    // 清空仓储当前保存的数据，使后续读取回到空状态。
    fun clear()
}

/** 最近使用的有效搜索词，按新到旧排列；不保存地点坐标或天气。 */
interface SearchHistoryRepository {
    // 读取并清理历史关键词，按去重后的最近使用顺序返回。
    fun recent(): List<String>
    // 去除首尾空白，忽略大小写去重并将新词移到队首，最多保留十条。
    fun record(query: String): List<String>
    // 清空仓储当前保存的数据，使后续读取回到空状态。
    fun clear()
}

/** 功能模块所需的领域依赖集合，便于装配与测试替换。 */
interface WeatherDependencies {
    val locations: LocationRepository
    val weather: WeatherRepository
    val sessions: LocationSessionRepository
    val searchHistory: SearchHistoryRepository
}
