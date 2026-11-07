package com.example.skymoment.weather

import android.content.Context
import androidx.fragment.app.Fragment
import com.alibaba.android.arouter.facade.annotation.Route
import com.alibaba.android.arouter.facade.template.IProvider
import com.example.skymoment.core.router.ServiceRoutes
import com.example.skymoment.core.service.IWeatherService

/**
 * 同时满足页面创建契约与 ARouter 服务接口。
 * 将天气页面工厂注册到天气组件服务地址。
 */
@Route(path = ServiceRoutes.WEATHER)
class WeatherService : IWeatherService, IProvider {
    /*
     * 该页面工厂无需维护额外初始化状态。
     */
    override fun init(context: Context) = Unit

    /*
     * 创建只携带会话 token 的天气 Fragment。
     */
    override fun createFragment(sessionToken: String): Fragment =
        WeatherFragment.forSession(sessionToken)
}
