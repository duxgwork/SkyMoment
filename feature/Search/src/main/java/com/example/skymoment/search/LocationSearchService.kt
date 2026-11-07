package com.example.skymoment.search

import android.content.Context
import android.content.Intent
import com.alibaba.android.arouter.facade.annotation.Route
import com.alibaba.android.arouter.facade.template.IProvider
import com.example.skymoment.core.router.LocationSearchContract
import com.example.skymoment.core.router.ServiceRoutes
import com.example.skymoment.core.service.ILocationSearchService

/**
 * 同时实现业务工厂契约和 ARouter 服务接口。
 * 把搜索页面工厂注册为组件服务。
 */
@Route(path = ServiceRoutes.LOCATION_SEARCH)
class LocationSearchService : ILocationSearchService, IProvider {
    /*
     * 该服务无额外共享依赖，不需要初始化状态。
     */
    override fun init(context: Context) = Unit

    /*
     * 由搜索模块内部构造选择模式 Intent。
     */
    override fun createSelectionIntent(context: Context): Intent =
        // 指向搜索 Activity 并声明应回传选择结果。
        Intent(
            context,
            SearchActivity::class.java
        ).putExtra(LocationSearchContract.RETURN_SELECTION, true)
}
