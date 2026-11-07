package com.example.skymoment.datalayer.weather

import com.example.skymoment.domain.CurrentWeather
import com.example.skymoment.domain.Location
import com.example.skymoment.domain.LocationSessionRepository
import java.util.UUID

/**
 * 保存唯一有效地点会话，新选择替换旧 token，进程结束后自动丢失。
 */
class InMemoryLocationSessionRepository : LocationSessionRepository {
    /**
     * 将 token、地点和可选首屏天气封装为同一不可变会话。
     */
    private data class Session(
        // 保存本次选择唯一的随机会话标识。
        val token: String,
        // 保存会话对应地点，避免跨组件传完整对象。
        val location: Location,
        // 可选保存启动已获取的当前天气供首屏复用。
        val current: CurrentWeather?
    )

    // 进程内只保留当前一次地点选择。
    private var selection: Session? = null

    /*
     * 以新的随机 token 替换旧会话，并可同时保存首屏当前天气。
     * 串行创建会话，避免并发选择相互交叉。
     */
    @Synchronized
    override fun createSession(location: Location, initialWeather: CurrentWeather?): String =
        // 生成新 UUID 并整体替换会话，使旧 token 立即失效。
        UUID.randomUUID().toString().also { selection = Session(it, location, initialWeather) }

    /*
     * 仅在 token 与当前会话匹配时返回地点。
     * 同步读取地点与当前 token 的对应关系。
     */
    @Synchronized
    override fun findLocation(token: String): Location? =
        // 只向当前有效 token 返回地点，过期标识得到 null。
        selection?.takeIf { it.token == token }?.location

    /*
     * 按有效会话读取启动天气，供天气页复用并减少重复请求。
     * 同步读取首屏天气，保持与当前地点一致。
     */
    @Synchronized
    override fun findInitialWeather(token: String): CurrentWeather? =
        // 仅对有效会话暴露首次当前天气。
        selection?.takeIf { it.token == token }?.current

    /*
     * 清除地点、token 和首屏天气，旧页面不可继续解析已失效会话。
     * 同步清理，避免读到部分清空的状态。
     */
    @Synchronized
    override fun clear() {
        // 释放整个会话，后续解析均视为失效。
        selection = null
    }
}
