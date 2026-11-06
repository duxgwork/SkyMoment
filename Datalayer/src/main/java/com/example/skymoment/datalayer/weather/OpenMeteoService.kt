package com.example.skymoment.datalayer.weather

import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Query

/** Retrofit 地理编码接口，仅描述供应商路径与查询参数。 */
interface OpenMeteoGeocodingService {
    @GET("v1/search")
    // 声明地点搜索端点，查询参数由 Retrofit 编码。
    suspend fun search(
        @Query("name") query: String,
        @Query("language") language: String,
        @Query("count") count: Int = 50,
        @Query("format") format: String = "json",
    ): Response<OpenMeteoGeocodingResponseDto>

    @GET("v1/get")
    // 按供应商地点 ID 获取指定语言的名称与行政信息。
    suspend fun get(
        @Query("id") id: String,
        @Query("language") language: String
    ): Response<OpenMeteoLocationDto>
}

/** Retrofit 天气接口，以独立字段集分别请求当前、小时和逐日天气。 */
interface OpenMeteoForecastService {
    @GET("v1/forecast")
    // 请求未来二十四小时字段，使用 Unix 时间避免夏令时重名歧义。
    suspend fun hourly(
        @Query("latitude") latitude: Double,
        @Query("longitude") longitude: Double,
        @Query("timezone") timezone: String,
        @Query("hourly") variables: String = "temperature_2m,weather_code,is_day",
        @Query("forecast_hours") hours: Int = 24,
        @Query("temperature_unit") temperatureUnit: String = "celsius",
        @Query("timeformat") timeFormat: String = "unixtime",
    ): Response<OpenMeteoForecastResponseDto>

    @GET("v1/forecast")
    // 只请求当前天气字段，固定摄氏和米每秒单位。
    suspend fun current(
        @Query("latitude") latitude: Double,
        @Query("longitude") longitude: Double,
        @Query("timezone") timezone: String,
        @Query("current") variables: String = "temperature_2m,relative_humidity_2m,wind_speed_10m,weather_code,is_day",
        @Query("temperature_unit") temperatureUnit: String = "celsius",
        @Query("wind_speed_unit") windUnit: String = "ms",
        @Query("timeformat") timeFormat: String = "unixtime",
    ): Response<OpenMeteoForecastResponseDto>

    @GET("v1/forecast")
    // 请求七日天气代码和最高／最低温，日期使用当地 ISO 日历。
    suspend fun daily(
        @Query("latitude") latitude: Double,
        @Query("longitude") longitude: Double,
        @Query("timezone") timezone: String,
        @Query("daily") variables: String = "weather_code,temperature_2m_max,temperature_2m_min",
        @Query("forecast_days") days: Int = 7,
        @Query("temperature_unit") temperatureUnit: String = "celsius",
        @Query("wind_speed_unit") windUnit: String = "ms",
        @Query("timeformat") timeFormat: String = "iso8601",
    ): Response<OpenMeteoForecastResponseDto>
}
