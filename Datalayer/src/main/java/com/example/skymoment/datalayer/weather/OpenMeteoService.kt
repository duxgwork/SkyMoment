package com.example.skymoment.datalayer.weather

import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * Retrofit 地理编码接口，仅描述供应商路径与查询参数。
 */
interface OpenMeteoGeocodingService {
    /*
     * 声明地点搜索端点，查询参数由 Retrofit 编码。
     * 将搜索调用映射到公共地理编码搜索路径。
     * 保留 HTTP 状态和搜索响应体，供执行器统一处理。
     */
    @GET("v1/search")
    suspend fun search(
        // 传递原关键词或规范化后的查询变体。
        @Query("name") query: String,
        // 请求指定语言的地点名称。
        @Query("language") language: String,
        // 每个查询变体最多请求五十条候选。
        @Query("count") count: Int = 50,
        // 明确使用 JSON 响应格式。
        @Query("format") format: String = "json",
    ): Response<OpenMeteoGeocodingResponseDto>

    /*
     * 按供应商地点 ID 获取指定语言的名称与行政信息。
     * 将地点详情调用映射到按 ID 查询路径。
     * 返回单个地点对象及 HTTP 状态。
     */
    @GET("v1/get")
    suspend fun get(
        // 传递供应商地点 ID，不传本地会话 token。
        @Query("id") id: String,
        // 指定地点名称所需语言。
        @Query("language") language: String
    ): Response<OpenMeteoLocationDto>
}

/**
 * Retrofit 天气接口，以独立字段集分别请求当前、小时和逐日天气。
 */
interface OpenMeteoForecastService {
    /*
     * 请求未来二十四小时字段，使用 Unix 时间避免夏令时重名歧义。
     * 小时预报使用公共天气查询路径。
     * 返回带小时数组的共用天气响应。
     */
    @GET("v1/forecast")
    suspend fun hourly(
        // 按名称传递查询纬度，避免坐标顺序混淆。
        @Query("latitude") latitude: Double,
        // 按名称传递查询经度。
        @Query("longitude") longitude: Double,
        // 按地点时区组织预报时间。
        @Query("timezone") timezone: String,
        // 仅请求小时温度、天气代码和昼夜标识。
        @Query("hourly") variables: String = "temperature_2m,weather_code,is_day",
        // 以当前小时为起点请求二十四小时。
        @Query("forecast_hours") hours: Int = 24,
        // 固定摄氏温度，避免展示单位不一致。
        @Query("temperature_unit") temperatureUnit: String = "celsius",
        // 使用 Unix 秒，避免当地小时重复产生歧义。
        @Query("timeformat") timeFormat: String = "unixtime",
    ): Response<OpenMeteoForecastResponseDto>

    /*
     * 只请求当前天气字段，固定摄氏和米每秒单位。
     * 当前天气使用公共天气查询路径。
     * 返回带当前指标的共用天气响应。
     */
    @GET("v1/forecast")
    suspend fun current(
        // 传递设备或已选地点纬度。
        @Query("latitude") latitude: Double,
        // 传递设备或已选地点经度。
        @Query("longitude") longitude: Double,
        // 首次坐标查询可用 auto，之后使用地点时区。
        @Query("timezone") timezone: String,
        // 请求首屏展示所需的五项当前天气指标。
        @Query("current") variables: String = "temperature_2m,relative_humidity_2m,wind_speed_10m,weather_code,is_day",
        // 固定当前温度单位为摄氏度。
        @Query("temperature_unit") temperatureUnit: String = "celsius",
        // 固定风速单位为米每秒。
        @Query("wind_speed_unit") windUnit: String = "ms",
        // 当前天气有效时刻使用 Unix 秒。
        @Query("timeformat") timeFormat: String = "unixtime",
    ): Response<OpenMeteoForecastResponseDto>

    /*
     * 请求七日天气代码和最高／最低温，日期使用当地 ISO 日历。
     * 七日预报使用公共天气查询路径。
     * 返回带逐日数组的共用天气响应。
     */
    @GET("v1/forecast")
    suspend fun daily(
        // 传递所选地点纬度。
        @Query("latitude") latitude: Double,
        // 传递所选地点经度。
        @Query("longitude") longitude: Double,
        // 指定当地日历日期采用的时区。
        @Query("timezone") timezone: String,
        // 仅请求每天概况与最高、最低温。
        @Query("daily") variables: String = "weather_code,temperature_2m_max,temperature_2m_min",
        // 请求包含今天的七天数据。
        @Query("forecast_days") days: Int = 7,
        // 固定每日温度单位为摄氏度。
        @Query("temperature_unit") temperatureUnit: String = "celsius",
        // 沿用统一米制风速参数，当前字段集不读取风速。
        @Query("wind_speed_unit") windUnit: String = "ms",
        // 日预报使用可直接解析的当地日期格式。
        @Query("timeformat") timeFormat: String = "iso8601",
    ): Response<OpenMeteoForecastResponseDto>
}
