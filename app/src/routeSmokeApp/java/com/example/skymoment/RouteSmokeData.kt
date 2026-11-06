package com.example.skymoment

import android.app.Activity
import com.example.skymoment.core.router.ARouterNavigator
import com.example.skymoment.core.router.ServiceLocator
import com.example.skymoment.core.router.ServiceRoutes
import com.example.skymoment.datalayer.weather.LocationQueries
import com.example.skymoment.domain.CurrentWeather
import com.example.skymoment.domain.Location
import com.example.skymoment.domain.WeatherDependencies
import java.time.Instant
import java.time.ZoneId

/** 仅 routeSmoke=true 编入被测应用；避免测试 APK 的 L8 子集遮蔽应用 java.time。 */
object RouteSmokeData {
    // 在压缩包中验证 ICU 数据和中文拼音转换仍然可用。
    @JvmStatic fun checkPinyin(): Boolean = LocationQueries().variants("海淀区").contains("haidian")
    // 在发布冒烟专用源集中创建合成地点会话，不依赖真实定位或网络。
    @JvmStatic fun select(): String =
        ServiceLocator.get(ServiceRoutes.APPLICATION, WeatherDependencies::class.java).sessions.select(Location(
            "test", "synthetic-release", "示例区", listOf("示例市"), 1.0, 2.0, ZoneId.of("Asia/Shanghai")),
            CurrentWeather(0.0, 65.0, 0.0, 0, true, Instant.now(), emptyList()))

    // 通过真实搜索路由打开页面，供压缩包冒烟验证。
    @JvmStatic fun openSearch(activity: Activity) = ARouterNavigator.search(activity)
    // 通过真实首页路由携带合成会话，供压缩包冒烟验证。
    @JvmStatic fun openHome(activity: Activity, token: String) = ARouterNavigator.home(activity, token)
}
