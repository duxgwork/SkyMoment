package com.example.skymoment.core.router

/**
 * Intent 与 Fragment arguments 的传参键；只传随机会话标识，不传地点或天气对象。
 */
object NavigationExtras {
    // 统一 Intent 与 Fragment 参数中的随机会话键。
    const val SESSION_TOKEN = "session_token"
}
