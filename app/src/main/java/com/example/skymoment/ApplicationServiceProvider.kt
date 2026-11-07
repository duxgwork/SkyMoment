package com.example.skymoment

import android.content.Context
import com.alibaba.android.arouter.facade.annotation.Route
import com.alibaba.android.arouter.facade.template.IProvider
import com.example.skymoment.core.router.ServiceRoutes
import com.example.skymoment.core.service.IApplicationService
import com.example.skymoment.datalayer.weather.MmkvSearchHistoryRepository
import com.example.skymoment.datalayer.weather.WeatherDependencyFactory
import com.example.skymoment.domain.DeviceLocationProvider

/**
 * ARouter 首次访问时创建并缓存的进程单例，统一管理应用依赖。
 * 同时实现宿主依赖契约与 ARouter 服务生命周期。
 */
@Route(path = ServiceRoutes.APPLICATION)
class ApplicationServiceProvider : IApplicationService, IProvider {
    // 在服务初始化后持有应用上下文供依赖构造。
    private lateinit var context: Context

    /*
     * ServiceLocator 首次 navigation() 时，ARouter 的 LogisticsCenter 调用此回调后缓存实例。
     */
    override fun init(context: Context) {
        // 只保存 Application Context，避免单例持有页面导致泄漏。
        this.context = context.applicationContext
    }

    // 定位能力首次被请求时才创建，并在服务实例内复用。
    override val deviceLocation: DeviceLocationProvider by lazy {
        // 构造使用系统位置管理器的定位实现。
        AndroidDeviceLocationProvider(context)
    }

    // 整组天气依赖只装配一次，确保会话和限流门共享。
    private val weatherDependencies by lazy {
        // 交由数据层工厂封装具体网络和仓储实现。
        WeatherDependencyFactory.create(
            // 注入只保存搜索词的本地历史仓储。
            searchHistory = MmkvSearchHistoryRepository(context),
            // 将系统逆地理编码方法作为设备名称解析回调。
            deviceLocationNames = AndroidLocationNameResolver(context)::resolveName,
        )
    }

    /*
     * 对外提供共享地点仓储，不重复创建服务。
     */
    override val locations get() = weatherDependencies.locations

    /*
     * 对外提供共享天气仓储，沿用同一请求策略。
     */
    override val weather get() = weatherDependencies.weather

    /*
     * 对外提供唯一会话仓储，让搜索与天气解析同一 token。
     */
    override val sessions get() = weatherDependencies.sessions

    /*
     * 对外提供共享历史词仓储，使页面切换后历史一致。
     */
    override val searchHistory get() = weatherDependencies.searchHistory
}
