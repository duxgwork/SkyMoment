package com.example.skymoment.domain

import com.example.skymoment.domain.Coordinates

/** 前台单次定位契约；Android 权限与系统 API 由应用层实现。 */
interface DeviceLocationProvider {
    // 近似或精确位置任一授权即可进入前台定位流程。
    fun hasPermission(): Boolean
    // 获取一次前台位置；无可用位置返回 null，取消时释放系统回调。
    suspend fun locate(): Coordinates?
}
