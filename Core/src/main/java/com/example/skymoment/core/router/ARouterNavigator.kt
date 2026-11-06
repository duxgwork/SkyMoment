package com.example.skymoment.core.router

import android.app.Activity
import android.app.Application
import android.content.Intent
import com.alibaba.android.arouter.facade.Postcard
import com.alibaba.android.arouter.facade.callback.NavigationCallback
import com.alibaba.android.arouter.launcher.ARouter

/** 集中执行页面路由和返回栈策略，隐藏 ARouter 调用细节。 */
object ARouterNavigator {
    // 通过首页路由传递会话，复用已有首页以避免叠加天气页面。
    fun home(activity: Activity, sessionToken: String? = null) {
        ARouter.getInstance().build(Routes.HOME)
            .withString(NavigationExtras.SESSION_TOKEN, sessionToken)
            .withFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .navigation(activity, failureCallback(activity))
    }

    // 打开独立搜索路由，通过通用回调处理到达或失败。
    fun search(activity: Activity) {
        ARouter.getInstance().build(Routes.SEARCH)
            .withFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .navigation(activity, failureCallback(activity))
    }

    // 处理路由到达、丢失和拦截，避免遗留无效的过渡页面。
    private fun failureCallback(activity: Activity) =
        object : NavigationCallback {
            // 路由已经匹配，无需额外处理。
            override fun onFound(postcard: Postcard) = Unit
            // 目标页面已打开时结束原过渡页；同一页面复用时保留当前实例。
            override fun onArrival(postcard: Postcard) {
                if (activity.javaClass != postcard.destination) activity.finish()
            }

            // 路由缺失时结束当前过渡页。
            override fun onLost(postcard: Postcard) {
                activity.finish()
            }

            // 路由被拦截时结束当前过渡页。
            override fun onInterrupt(postcard: Postcard) {
                activity.finish()
            }
        }

    // 使用 Application 初始化路由，避免把页面 Context 保存在全局对象中。
    fun initialize(application: Application) {
        // 不启用路由日志；Intent 仅传递不含地点信息的随机 token。
        ARouter.init(application)
    }
}
