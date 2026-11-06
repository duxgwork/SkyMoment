package com.example.skymoment.datalayer.weather

import com.example.skymoment.domain.CurrentWeather
import com.example.skymoment.domain.Location
import com.example.skymoment.domain.LocationSessionRepository
import java.util.UUID

/** 保存唯一有效地点会话，新选择替换旧 token，进程结束后自动丢失。 */
class InMemoryLocationSessionRepository : LocationSessionRepository {
    /** 将 token、地点和可选首屏天气封装为同一不可变会话。 */
    private data class Session(
        val token: String,
        val location: Location,
        val current: CurrentWeather?
    )

    private var selection: Session? = null

    @Synchronized
    // 以新的随机 token 替换旧会话，并可同时保存首屏当前天气。
    override fun select(location: Location, initialWeather: CurrentWeather?): String =
        UUID.randomUUID().toString().also { selection = Session(it, location, initialWeather) }

    @Synchronized
    // 仅在 token 与当前会话匹配时返回地点。
    override fun resolve(token: String): Location? =
        selection?.takeIf { it.token == token }?.location

    @Synchronized
    // 按有效会话读取启动天气，供天气页复用并减少重复请求。
    override fun initialWeather(token: String): CurrentWeather? =
        selection?.takeIf { it.token == token }?.current

    @Synchronized
    // 清除地点、token 和首屏天气，旧页面不可继续解析已失效会话。
    override fun clear() {
        selection = null
    }
}
