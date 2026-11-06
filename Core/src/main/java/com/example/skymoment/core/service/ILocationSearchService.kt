package com.example.skymoment.core.service

import android.content.Context
import android.content.Intent

/** 搜索组件负责自己的 Intent，调用方不依赖具体 Activity。 */
interface ILocationSearchService {
    fun createSelectionIntent(context: Context): Intent
}
