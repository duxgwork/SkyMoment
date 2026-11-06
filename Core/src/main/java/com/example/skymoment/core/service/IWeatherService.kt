package com.example.skymoment.core.service

import androidx.fragment.app.Fragment

/** 天气组件提供页面工厂，调用方只传递随机会话 token。 */
interface IWeatherService {
    fun createFragment(sessionToken: String): Fragment
}
