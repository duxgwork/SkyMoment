package com.example.skymoment.core.service

import com.example.skymoment.domain.DeviceLocationProvider
import com.example.skymoment.domain.WeatherDependencies

/**
 * 宿主发布的应用服务契约；首页按此契约获取定位与天气依赖。
 **/
interface IApplicationService : WeatherDependencies {
    val deviceLocation: DeviceLocationProvider
}
