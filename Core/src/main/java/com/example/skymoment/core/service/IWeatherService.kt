package com.example.skymoment.core.service

import androidx.fragment.app.Fragment

/**
 * 天气组件提供页面工厂，调用方只传递随机会话 token。
 */
interface IWeatherService {
    /*
     * 根据进程内会话标识创建天气页面，隐藏具体 Fragment 类型。
     */
    fun createFragment(sessionToken: String): Fragment
}
