package com.example.skymoment.core.router

/**
 * 服务发现地址；每个 ARouter group 只能由一个组件生成，避免生成类重名。
 */
object ServiceRoutes {
    // 天气组件页面工厂的服务地址。
    const val WEATHER = "/weather/service"

    // 搜索组件 Intent 工厂的服务地址。
    const val LOCATION_SEARCH = "/location/service"

    // 宿主应用依赖集合的服务地址。
    const val APPLICATION = "/services/application"
}
