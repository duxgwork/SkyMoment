package com.example.skymoment.core.router

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContract
import com.example.skymoment.core.service.ILocationSearchService

/**
 * 从首页选择地点时只返回随机会话 token；取消不会修改调用方的地点。
 */
class LocationSearchContract : ActivityResultContract<Unit, String?>() {
    /*
     * 标记为结果选择模式，避免选中后再通过路由创建或重启首页。
     */
    override fun createIntent(context: Context, input: Unit): Intent =
        // 按服务契约找到搜索组件，不直接依赖其 Activity 类。
        ServiceLocator.get(ServiceRoutes.LOCATION_SEARCH, ILocationSearchService::class.java)
            // 由搜索组件构造选择模式 Intent。
            .createSelectionIntent(context)

    /*
     * 只接受成功且非空的会话；返回键或无效结果统一交给调用方按取消处理。
     */
    override fun parseResult(resultCode: Int, intent: Intent?): String? =
        // 仅接受搜索页明确确认的选择结果。
        if (resultCode == Activity.RESULT_OK) {
            // 提取非空白会话标识，缺失结果按取消处理。
            intent?.getStringExtra(NavigationExtras.SESSION_TOKEN)?.takeIf { it.isNotBlank() }
            // 其他结果码均视为取消，不覆盖首页地点。
        } else null

    /**
     * 集中存放选择模式的共享参数名。
     */
    companion object {
        // 标识搜索应回传结果而非另行路由到首页。
        const val RETURN_SELECTION = "return_location_selection"
    }
}
