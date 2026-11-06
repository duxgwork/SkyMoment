package com.example.skymoment.core.router

/** 服务发现地址；每个 ARouter group 只能由一个组件生成，避免生成类重名。 */
object ServiceRoutes {
    const val WEATHER = "/weather/service"
    const val LOCATION_SEARCH = "/location/service"
    const val APPLICATION = "/services/application"
}
