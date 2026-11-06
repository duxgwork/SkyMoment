package com.example.skymoment

import android.content.Context
import com.alibaba.android.arouter.facade.annotation.Route
import com.alibaba.android.arouter.facade.template.IProvider
import com.example.skymoment.core.router.ServiceRoutes
import com.example.skymoment.core.service.IApplicationService
import com.example.skymoment.datalayer.weather.PreferencesSearchHistoryRepository
import com.example.skymoment.datalayer.weather.WeatherRepositoryFactory
import com.example.skymoment.domain.DeviceLocationProvider

/** ARouter 首次访问时创建并缓存的进程单例，统一管理应用依赖。 */
@Route(path = ServiceRoutes.APPLICATION)
class AppServices : IApplicationService, IProvider {
    private lateinit var context: Context

    // ServiceLocator 首次 navigation() 时，ARouter 的 LogisticsCenter 调用此回调后缓存实例。
    override fun init(context: Context) {
        // 只保存 Application Context，避免单例持有页面导致泄漏。
        this.context = context.applicationContext
    }

    override val deviceLocation: DeviceLocationProvider by lazy {
        AndroidDeviceLocationProvider(context)
    }
    private val weatherDependencies by lazy {
        WeatherRepositoryFactory.create(
            searchHistory = PreferencesSearchHistoryRepository(context),
            deviceLocationNames = AndroidLocationNameResolver(context)::resolve,
        )
    }

    override val locations get() = weatherDependencies.locations
    override val weather get() = weatherDependencies.weather
    override val sessions get() = weatherDependencies.sessions
    override val searchHistory get() = weatherDependencies.searchHistory
}
