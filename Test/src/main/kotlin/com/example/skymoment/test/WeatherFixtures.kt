package com.example.skymoment.test

import com.example.skymoment.domain.CurrentWeather
import com.example.skymoment.domain.Location
import java.time.Instant
import java.time.ZoneId

/** 每次调用产生独立、固定时间的样本，不读取网络、定位或系统时钟。 */
object WeatherFixtures {
    fun location(id: String = "1") = Location(
        "test", id, "示例区", listOf("示例市"), 31.2, 121.5, ZoneId.of("Asia/Shanghai")
    )
    fun current() = CurrentWeather(
        12.0, 50.0, 2.0, 3, true, Instant.parse("2026-11-06T00:00:00Z"), emptyList()
    )
}
