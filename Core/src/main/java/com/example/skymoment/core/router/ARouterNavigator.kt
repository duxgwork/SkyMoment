package com.example.skymoment.core.router

import android.app.Activity
import android.app.Application
import android.content.Intent
import com.alibaba.android.arouter.facade.Postcard
import com.alibaba.android.arouter.facade.callback.NavigationCallback
import com.alibaba.android.arouter.launcher.ARouter

/**
 * 集中执行页面路由和返回栈策略，隐藏 ARouter 调用细节。
 */
object ARouterNavigator {
    /*
     * 通过首页路由传递会话，复用已有首页以避免叠加天气页面。
     */
    fun home(activity: Activity, sessionToken: String? = null) {
        // 构建首页路由，目标组件由 ARouter 解析。
        ARouter.getInstance().build(Routes.HOME)
            // 只携带随机会话标识，避免传递完整地点数据。
            .withString(NavigationExtras.SESSION_TOKEN, sessionToken)
            // 复用已有首页并清除其上的页面，避免堆叠天气入口。
            .withFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            // 使用当前页面上下文导航，并统一处理到达或失败。
            .navigation(activity, failureCallback(activity))
    }

    /*
     * 打开独立搜索路由，通过通用回调处理到达或失败。
     */
    fun search(activity: Activity) {
        // 构建地点搜索页面路由。
        ARouter.getInstance().build(Routes.SEARCH)
            // 复用已存在的搜索页，避免重复加入返回栈。
            .withFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            // 执行搜索导航并使用同一组页面收尾回调。
            .navigation(activity, failureCallback(activity))
    }

    /*
     * 处理路由到达、丢失和拦截，避免遗留无效的过渡页面。
     */
    private fun failureCallback(activity: Activity) =
        // 创建与发起页面绑定的路由回调对象。
        object : NavigationCallback {
            /*
             * 路由已经匹配，无需额外处理。
             */
            override fun onFound(postcard: Postcard) = Unit

            /*
             * 目标页面已打开时结束原过渡页；同一页面复用时保留当前实例。
             */
            override fun onArrival(postcard: Postcard) {
                // 目标不是当前页面类型时结束旧页，避免留下重复入口。
                if (activity.javaClass != postcard.destination) activity.finish()
            }

            /*
             * 路由缺失时结束当前过渡页。
             */
            override fun onLost(postcard: Postcard) {
                // 路由目标丢失时结束发起页面，避免停留在无效入口。
                activity.finish()
            }

            /*
             * 路由被拦截时结束当前过渡页。
             */
            override fun onInterrupt(postcard: Postcard) {
                // 路由被拦截时结束发起页面，避免留下无法完成的跳转。
                activity.finish()
            }
        }

    /*
     * 使用 Application 初始化路由，避免把页面 Context 保存在全局对象中。
     */
    fun initialize(application: Application) {
        // 不启用路由日志；Intent 仅传递不含地点信息的随机 token。
        ARouter.init(application)
    }
}
