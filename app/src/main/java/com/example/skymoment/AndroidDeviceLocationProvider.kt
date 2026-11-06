package com.example.skymoment

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import com.example.skymoment.domain.Coordinates
import com.example.skymoment.domain.DeviceLocationProvider
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/** 仅请求一次前台位置；取消或拿到位置后立即释放所有定位回调。 */
class AndroidDeviceLocationProvider(context: Context) : DeviceLocationProvider {
    private val context = context.applicationContext
    private val manager = this.context.getSystemService(LocationManager::class.java)

    // 读取单项运行时权限，处理用户在系统设置中变更授权的情况。
    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    // 近似或精确位置任一授权即可进入前台定位流程。
    override fun hasPermission() = granted(Manifest.permission.ACCESS_COARSE_LOCATION) ||
            granted(Manifest.permission.ACCESS_FINE_LOCATION)

    @SuppressLint("MissingPermission") // hasPermission() 检查授权，调用仍处理随时撤权的 SecurityException。
    // 获取一次前台位置；无可用位置返回 null，取消时释放系统回调。
    override suspend fun locate(): Coordinates? = withContext(Dispatchers.Main.immediate) {
        if (!hasPermission() || manager == null || !LocationManagerCompat.isLocationEnabled(manager)) return@withContext null
        val candidates = buildList {
            add(LocationManager.NETWORK_PROVIDER)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(LocationManager.FUSED_PROVIDER)
            if (granted(Manifest.permission.ACCESS_FINE_LOCATION)) add(LocationManager.GPS_PROVIDER)
        }.filter { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
        if (candidates.isEmpty()) return@withContext null
        val signals = candidates.map { CancellationSignal() }
        try {
            suspendCancellableCoroutine { continuation ->
                continuation.invokeOnCancellation { signals.forEach { it.cancel() } }
                var remaining = candidates.size
                // 只接受有效且足够新的定位结果，所有候选耗尽后返回空结果。
                fun result(fix: android.location.Location?) {
                    if (!continuation.isActive) return
                    val age =
                        fix?.let { SystemClock.elapsedRealtimeNanos() - it.elapsedRealtimeNanos }
                    val coordinates = fix?.takeIf { age != null && age in 0..120_000_000_000L }
                        ?.let { runCatching { Coordinates(it.latitude, it.longitude) }.getOrNull() }
                    if (coordinates != null) continuation.resume(coordinates)
                    else if (--remaining == 0) continuation.resume(null)
                }
                candidates.forEachIndexed { index, provider ->
                    if (continuation.isActive) {
                        try {
                            LocationManagerCompat.getCurrentLocation(
                                manager, provider, signals[index],
                                ContextCompat.getMainExecutor(context), ::result
                            )
                        } catch (_: SecurityException) {
                            result(null)
                        } catch (_: IllegalArgumentException) {
                            result(null)
                        }
                    }
                }
            }
        } finally {
            signals.forEach { it.cancel() }
        }
    }
}
