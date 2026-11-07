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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * 仅请求一次前台位置；取消或拿到位置后立即释放所有定位回调。
 */
class AndroidDeviceLocationProvider(context: Context) : DeviceLocationProvider {
    // 只保留应用上下文，避免定位服务持有 Activity。
    private val context = context.applicationContext

    // 读取系统位置管理器，缺失时由定位入口返回空结果。
    private val manager = this.context.getSystemService(LocationManager::class.java)

    /*
     * 读取单项运行时权限，处理用户在系统设置中变更授权的情况。
     */
    private fun granted(permission: String) =
        // 实时查询权限，兼容用户从设置中撤回或授予授权。
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /*
     * 近似或精确位置任一授权即可进入前台定位流程。
     */
    override fun hasPermission() = granted(Manifest.permission.ACCESS_COARSE_LOCATION) ||
            // 精确位置授权同样允许进入定位流程。
            granted(Manifest.permission.ACCESS_FINE_LOCATION)

    /*
     * 获取一次前台位置；无可用位置返回 null，取消时释放系统回调。
     * hasPermission() 检查授权，调用仍处理随时撤权的 SecurityException。
     */
    @SuppressLint("MissingPermission")
    override suspend fun locate(): Coordinates? = withContext(Dispatchers.Main.immediate) {
        // 权限、系统服务或位置开关不可用时不发起定位。
        if (!hasPermission() || manager == null || !LocationManagerCompat.isLocationEnabled(manager)) return@withContext null
        // 汇总当前平台和权限允许的定位来源。
        val candidates = buildList {
            // 优先尝试无需精确授权的网络位置来源。
            add(LocationManager.NETWORK_PROVIDER)
            // 仅在支持的平台上加入系统融合位置来源。
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(LocationManager.FUSED_PROVIDER)
            // 只有精确位置授权时才请求 GPS 来源。
            if (granted(Manifest.permission.ACCESS_FINE_LOCATION)) add(LocationManager.GPS_PROVIDER)
            // 过滤被关闭或查询状态异常的 provider。
        }.filter { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
        // 没有可用来源时返回无位置结果。
        if (candidates.isEmpty()) return@withContext null
        // 为每个并行候选准备独立的取消信号。
        val signals = candidates.map { CancellationSignal() }
        try {
            // 把平台回调转换为可取消的挂起等待。
            suspendCancellableCoroutine { continuation ->
                // 上层取消时立即停止全部候选定位请求。
                continuation.invokeOnCancellation { signals.forEach { it.cancel() } }
                // 记录尚未返回有效结果的候选数量。
                var remaining = candidates.size

                /*
                 * 只接受有效且足够新的定位结果，所有候选耗尽后返回空结果。
                 */
                fun result(fix: android.location.Location?) {
                    // 协程已经完成或取消时忽略迟到的定位回调。
                    if (!continuation.isActive) return
                    // 准备计算定位结果的单调时钟年龄。
                    val age =
                        // 使用开机后时间差，避免系统日期调整影响新鲜度。
                        fix?.let { SystemClock.elapsedRealtimeNanos() - it.elapsedRealtimeNanos }
                    // 只接受不在未来且距今最多两分钟的定位。
                    val coordinates = fix?.takeIf { age != null && age in 0..120_000_000_000L }
                        // 把有效位置转换为领域坐标，非法数值回退为空。
                        ?.let { runCatching { Coordinates(it.latitude, it.longitude) }.getOrNull() }
                    // 首个有效候选立即完成定位等待。
                    if (coordinates != null) continuation.resume(coordinates)
                    // 所有候选都失败时完成为空，避免无限等待。
                    else if (--remaining == 0) continuation.resume(null)
                }
                // 向每个可用 provider 分别提交一次定位请求。
                candidates.forEachIndexed { index, provider ->
                    // 已有候选成功后不再启动额外请求。
                    if (continuation.isActive) {
                        try {
                            // 通过兼容 API 发起一次位置查询。
                            LocationManagerCompat.getCurrentLocation(
                                // 关联当前 provider 与其独立取消信号。
                                manager, provider, signals[index],
                                // 把回调调度到主线程并交给统一结果处理。
                                ContextCompat.getMainExecutor(context), ::result
                            )
                        } catch (_: SecurityException) {
                            // 将该候选计为失败，继续等待其他来源。
                            result(null)
                        } catch (_: IllegalArgumentException) {
                            // 同样将当前候选计为失败。
                            result(null)
                        }
                    }
                }
            }
        } finally {
            // 取消全部定位信号，释放尚未完成的系统回调。
            signals.forEach { it.cancel() }
        }
    }
}
