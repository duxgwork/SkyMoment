package com.example.skymoment

import android.app.Application
import com.example.skymoment.core.router.ARouterNavigator

/** 应用进程入口，负责路由初始化，不持有页面业务状态。 */
class SkyMomentApplication : Application() {
    // 进程启动时初始化路由服务，为后续页面装配依赖。
    override fun onCreate() {
        super.onCreate()
        ARouterNavigator.initialize(this)
    }
}
