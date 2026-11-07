package com.example.skymoment.core.service

import android.content.Context
import android.content.Intent

/**
 * 搜索组件负责自己的 Intent，调用方不依赖具体 Activity。
 */
interface ILocationSearchService {
    /*
     * 由搜索组件构造可返回地点选择结果的页面 Intent。
     */
    fun createSelectionIntent(context: Context): Intent
}
