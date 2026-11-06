package com.example.skymoment.core.router

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContract
import com.example.skymoment.core.service.ILocationSearchService

/** 从首页选择地点时只返回随机会话 token；取消不会修改调用方的地点。 */
class LocationSearchContract : ActivityResultContract<Unit, String?>() {
    // 标记为结果选择模式，避免选中后再通过路由创建或重启首页。
    override fun createIntent(context: Context, input: Unit): Intent =
        ServiceLocator.get(ServiceRoutes.LOCATION_SEARCH, ILocationSearchService::class.java)
            .createSelectionIntent(context)

    // 只接受成功且非空的会话；返回键或无效结果统一交给调用方按取消处理。
    override fun parseResult(resultCode: Int, intent: Intent?): String? =
        if (resultCode == Activity.RESULT_OK) {
            intent?.getStringExtra(NavigationExtras.SESSION_TOKEN)?.takeIf { it.isNotBlank() }
        } else null

    companion object {
        const val RETURN_SELECTION = "return_location_selection"
    }
}
