package com.example.skymoment.domain

/**
 * 按关键词查找候选；有效语言标签由实现适配，取消必须继续抛出。
 */
fun interface LocationSearchRepository {
    suspend fun searchLocations(query: String, language: String): DataResult<List<Location>>
}

/**
 * 仅更新展示名称和行政路径，保留来源、ID、坐标与时区；取消必须继续抛出。
 */
fun interface LocationNameRepository {
    suspend fun refreshLocationName(location: Location, language: String): DataResult<Location>
}

/**
 * 装配层使用的地点能力集合，消费者按实际用途依赖较小的接口。
 */
interface LocationRepository : LocationSearchRepository, LocationNameRepository

/**
 * 按设备坐标取得当前天气和供应商解析的地点时区，不能用默认失败冒充支持。
 */
fun interface CoordinateWeatherRepository {
    suspend fun getWeatherAt(coordinates: Coordinates, language: String): DataResult<LocatedWeather>
}

/**
 * 读取已选地点的三个天气区域；合法空结果、业务失败与取消保持各自语义。
 */
interface SelectedLocationWeatherRepository {
    suspend fun getCurrentWeather(location: Location, language: String): DataResult<CurrentWeather>
    suspend fun getDailyForecast(location: Location, language: String): DataResult<Forecast>
    suspend fun getHourlyForecast(location: Location, language: String): DataResult<HourlyForecast>
}

/**
 * 装配层聚合两种天气读取能力，实现必须显式满足全部契约。
 */
interface WeatherRepository : CoordinateWeatherRepository, SelectedLocationWeatherRepository

/**
 * 仅在进程内保留当前选择，新的选择使之前的 token 失效。
 */
interface LocationSessionRepository {
    /*
     * 创建新的随机会话并使此前选择失效，可附带首屏天气供复用。
     */
    fun createSession(location: Location, initialWeather: CurrentWeather? = null): String

    /*
     * 仅解析当前有效的内存会话，失效 token 返回 null。
     */
    fun findLocation(token: String): Location?

    /*
     * 按有效会话读取启动天气，供天气页复用并减少重复请求。
     */
    fun findInitialWeather(token: String): CurrentWeather? = null

    /*
     * 清空仓储当前保存的数据，使后续读取回到空状态。
     */
    fun clear()
}

/**
 * 最近使用的有效搜索词，按新到旧排列；不保存地点坐标或天气。
 */
interface SearchHistoryRepository {
    /*
     * 读取并清理历史关键词，按去重后的最近使用顺序返回。
     */
    fun getRecentQueries(): List<String>

    /*
     * 去除首尾空白，忽略大小写去重并将新词移到队首，最多保留十条。
     */
    fun recordQuery(query: String): List<String>

    /*
     * 清空仓储当前保存的数据，使后续读取回到空状态。
     */
    fun clear()
}
