package com.example.skymoment.datalayer.weather

import android.content.Context
import com.example.skymoment.domain.SearchHistoryRepository
import com.tencent.mmkv.MMKV
import org.json.JSONArray
import java.util.Locale

/**
 * 只持久化最近十个关键词；JSON 数组保留最近使用顺序。
 */
class MmkvSearchHistoryRepository(context: Context) : SearchHistoryRepository {
    private val applicationContext = context.applicationContext

    // 首次读写时初始化，避免仅装配天气依赖时就打开本地存储。
    private val storage by lazy {
        MMKV.initialize(applicationContext)
        requireNotNull(MMKV.mmkvWithID(STORAGE_ID, MMKV.SINGLE_PROCESS_MODE)) {
            "Unable to open search history storage"
        }
    }

    override fun getRecentQueries(): List<String> = synchronized(storageLock) {
        val json = storage.decodeString(QUERIES_KEY, "[]")
        // 只捕获数据解析错误；初始化或存储错误不应被误报为空历史。
        runCatching {
            val values = JSONArray(json)
            (0 until values.length()).map { values.getString(it).trim() }
                .filter { it.codePointCount(0, it.length) >= 2 }
                .distinctBy { it.lowercase(Locale.ROOT) }
                .take(HISTORY_LIMIT)
        }.getOrDefault(emptyList())
    }

    override fun recordQuery(query: String): List<String> = synchronized(storageLock) {
        val term = query.trim()
        val previous = getRecentQueries()
        if (term.codePointCount(0, term.length) < 2) return@synchronized previous
        val updated =
            (listOf(term) + previous.filterNot { it.equals(term, ignoreCase = true) })
                .take(HISTORY_LIMIT)
        // MMKV 直接更新内存映射文件，并返回本次写入是否成功。
        check(storage.encode(QUERIES_KEY, JSONArray(updated).toString())) {
            "Unable to save search history"
        }
        updated
    }

    override fun clear() = synchronized(storageLock) {
        storage.removeValueForKey(QUERIES_KEY)
    }

    private companion object {
        const val STORAGE_ID = "search_history"
        const val QUERIES_KEY = "queries"
        const val HISTORY_LIMIT = 10

        // 多个仓储实例共享同一文件，读改写必须使用同一把进程内锁。
        val storageLock = Any()
    }
}
