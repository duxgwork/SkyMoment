package com.example.skymoment.test

import com.example.skymoment.domain.CurrentWeather
import com.example.skymoment.domain.Location
import com.example.skymoment.domain.LocationSessionRepository

/** 单会话替身；确定性 token 便于断言，功能层测试无需依赖 Datalayer 实现。 */
class FakeLocationSessionRepository : LocationSessionRepository {
    private var generation = 0
    private var token: String? = null
    private var location: Location? = null
    private var weather: CurrentWeather? = null

    override fun select(location: Location, initialWeather: CurrentWeather?): String {
        this.location = location
        weather = initialWeather
        return "test-session-${++generation}".also { token = it }
    }
    override fun resolve(token: String): Location? = location.takeIf { token == this.token }
    override fun initialWeather(token: String): CurrentWeather? = weather.takeIf { token == this.token }
    override fun clear() { token = null; location = null; weather = null }
}
