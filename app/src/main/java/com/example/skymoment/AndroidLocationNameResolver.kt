package com.example.skymoment

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Build
import com.example.skymoment.domain.Location
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * 名称独立解析，不阻塞当前天气；失败由调用方保留原来的地点展示。
 */
class AndroidLocationNameResolver(context: Context) {
    // 名称解析仅持有应用上下文，避免页面泄漏。
    private val context = context.applicationContext

    // 旧版阻塞接口所需线程在首次使用时创建。
    private val legacyExecutor by lazy {
        // 使用独立守护线程串行执行名称解析，避免阻塞界面。
        Executors.newSingleThreadExecutor { Thread(it, "location-name").apply { isDaemon = true } }
    }

    /*
     * 按有效语言执行系统逆地理编码，只提取行政名称；取消或失败时不制造地点信息。
     */
    suspend fun resolveName(location: Location, language: String): Location? {
        // 设备没有逆地理编码实现时直接保留原名称。
        if (!Geocoder.isPresent()) return null
        // 按当前界面语言创建系统地理编码器。
        val geocoder = Geocoder(context, Locale.forLanguageTag(language))
        // 将地址回调转换为可取消的挂起结果。
        val addresses = suspendCancellableCoroutine<List<Address>> { continuation ->
            // 原子标记保证取消与多个回调之间只完成一次。
            val completed = AtomicBoolean(false)

            /*
             * 使用一次性完成标记恢复等待者，避免重复回调或取消后的回调覆盖结果。
             */
            fun complete(addresses: List<Address>) {
                // 仅首个完成事件恢复等待者，后续回调直接忽略。
                if (completed.compareAndSet(false, true)) continuation.resume(addresses)
            }
            // Android 13 及以上使用非阻塞名称回调。
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // 取消时禁止迟到地址结果再恢复协程。
                continuation.invokeOnCancellation { completed.set(true) }
                try {
                    // 按设备坐标请求逆地理编码。
                    geocoder.getFromLocation(
                        // 传入地点纬度，避免名称查询偏离天气坐标。
                        location.latitude,
                        // 传入地点经度，与纬度保持同一地点。
                        location.longitude,
                        // 只请求一个最匹配地址，减少无用返回。
                        1,
                        // 通过系统监听器接收名称或错误结果。
                        object : Geocoder.GeocodeListener {
                            /*
                             * 把系统返回的地址列表交给统一完成入口。
                             */
                            override fun onGeocode(addresses: MutableList<Address>) =
                                // 把成功返回的地址列表交给一次性完成逻辑。
                                complete(addresses)

                            /*
                             * 将系统译名错误转换为空地址，由上层保留现有名称。
                             */
                            override fun onError(errorMessage: String?) = complete(emptyList())
                        })
                } catch (_: Exception) {
                    // 返回空地址列表，保持已有天气与地点展示。
                    complete(emptyList())
                }
            } else {
                // 提交旧版地理编码任务并保存可取消句柄。
                val future = legacyExecutor.submit {
                    // 仅在兼容分支允许调用平台已弃用的同步方法。
                    @Suppress("DEPRECATION")
                    // 把阻塞解析异常转换为可回退的空结果。
                    val result = runCatching {
                        // 在独立线程执行系统逆地理编码。
                        geocoder.getFromLocation(
                            // 使用原地点纬度查询行政名称。
                            location.latitude,
                            // 使用原地点经度查询行政名称。
                            location.longitude,
                            // 限制旧版接口同样只返回一个地址。
                            1
                        )
                    }
                        // 异常或系统返回 null 时统一为空地址列表。
                        .getOrNull().orEmpty()
                    // 完成名称等待，不把平台异常传到天气页面。
                    complete(result)
                }
                // 取消后阻止迟到回调并尝试中断后台任务。
                continuation.invokeOnCancellation { completed.set(true); future.cancel(true) }
            }
        }
        // 没有返回地址时不生成虚构名称。
        val address = addresses.firstOrNull() ?: return null
        // 只取区县／城市等行政名称，不用门牌、街道或 POI 作为天气地点名。
        val regions = listOfNotNull(
            // 优先提取区县、城市和次级行政区域。
            address.subLocality, address.locality, address.subAdminArea,
            // 再用省级行政区与国家补充较粗层级。
            address.adminArea, address.countryName
            // 去掉空白与重复行政名称，保持由细到粗顺序。
        ).map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        // 无法提取任何行政名称时保持原地点。
        val name = regions.firstOrNull() ?: return null
        // 仅更新名称及剩余行政路径，保留坐标与时区。
        return location.copy(name = name, administrativePath = regions.drop(1))
    }
}
