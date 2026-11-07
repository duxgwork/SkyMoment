package com.example.skymoment.domain

/**
 * 首页启动只需要定位、坐标天气与共享会话。
 */
interface HomeDependencies {
    val deviceLocation: DeviceLocationProvider
    val weather: CoordinateWeatherRepository
    val sessions: LocationSessionRepository
}

/**
 * 搜索页面无需访问天气与设备定位。
 */
interface SearchDependencies {
    val locations: LocationSearchRepository
    val sessions: LocationSessionRepository
    val searchHistory: SearchHistoryRepository
}

/**
 * 天气页面无需访问搜索历史和设备定位。
 */
interface WeatherDisplayDependencies {
    val locations: LocationNameRepository
    val weather: SelectedLocationWeatherRepository
    val sessions: LocationSessionRepository
}

/**
 * 工厂和宿主使用的聚合视图，页面通过各自的窄接口获取同一组实例。
 */
interface WeatherDependencies : SearchDependencies, WeatherDisplayDependencies {
    override val locations: LocationRepository
    override val weather: WeatherRepository
}
