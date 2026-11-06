package com.example.skymoment

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Build
import com.example.skymoment.domain.Location
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/** 名称独立解析，不阻塞当前天气；失败由调用方保留原来的地点展示。 */
class AndroidLocationNameResolver(context: Context) {
    private val context = context.applicationContext
    private val legacyExecutor by lazy {
        Executors.newSingleThreadExecutor { Thread(it, "location-name").apply { isDaemon = true } }
    }

    // 按有效语言执行系统逆地理编码，只提取行政名称；取消或失败时不制造地点信息。
    suspend fun resolve(location: Location, language: String): Location? {
        if (!Geocoder.isPresent()) return null
        val geocoder = Geocoder(context, Locale.forLanguageTag(language))
        val addresses = suspendCancellableCoroutine<List<Address>> { continuation ->
            val completed = AtomicBoolean(false)
            // 使用一次性完成标记恢复等待者，避免重复回调或取消后的回调覆盖结果。
            fun complete(addresses: List<Address>) {
                if (completed.compareAndSet(false, true)) continuation.resume(addresses)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                continuation.invokeOnCancellation { completed.set(true) }
                try {
                    geocoder.getFromLocation(
                        location.latitude,
                        location.longitude,
                        1,
                        object : Geocoder.GeocodeListener {
                            // 把系统返回的地址列表交给统一完成入口。
                            override fun onGeocode(addresses: MutableList<Address>) =
                                complete(addresses)

                            // 将系统译名错误转换为空地址，由上层保留现有名称。
                            override fun onError(errorMessage: String?) = complete(emptyList())
                        })
                } catch (_: Exception) {
                    complete(emptyList())
                }
            } else {
                val future = legacyExecutor.submit {
                    @Suppress("DEPRECATION")
                    val result = runCatching {
                        geocoder.getFromLocation(
                            location.latitude,
                            location.longitude,
                            1
                        )
                    }
                        .getOrNull().orEmpty()
                    complete(result)
                }
                continuation.invokeOnCancellation { completed.set(true); future.cancel(true) }
            }
        }
        val address = addresses.firstOrNull() ?: return null
        // 只取区县／城市等行政名称，不用门牌、街道或 POI 作为天气地点名。
        val regions = listOfNotNull(
            address.subLocality, address.locality, address.subAdminArea,
            address.adminArea, address.countryName
        ).map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        val name = regions.firstOrNull() ?: return null
        return location.copy(name = name, administrativePath = regions.drop(1))
    }
}
