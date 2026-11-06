package com.example.skymoment.datalayer.weather

import android.content.Context
import com.example.skymoment.domain.SearchHistoryRepository
import org.json.JSONArray

/** 只持久化最近十个关键词；JSON 数组保留顺序，apply 将磁盘写入交给后台完成。 */
class PreferencesSearchHistoryRepository(context: Context) : SearchHistoryRepository {
    private val preferences =
        context.applicationContext.getSharedPreferences("search_history", Context.MODE_PRIVATE)

    @Synchronized
    // 读取并清理历史关键词，按去重后的最近使用顺序返回。
    override fun recent(): List<String> = runCatching {
        val values = JSONArray(preferences.getString("queries", "[]"))
        (0 until values.length()).map { values.getString(it).trim() }
            .filter { it.codePointCount(0, it.length) >= 2 }
            .distinctBy { it.lowercase(java.util.Locale.ROOT) }
            .take(10)
    }.getOrDefault(emptyList())

    @Synchronized
    // 去除首尾空白，忽略大小写去重并将新词移到队首，最多保留十条。
    override fun record(query: String): List<String> {
        val term = query.trim()
        val previous = recent()
        if (term.codePointCount(0, term.length) < 2) return previous
        val updated =
            (listOf(term) + previous.filterNot { it.equals(term, ignoreCase = true) }).take(10)
        preferences.edit().putString("queries", JSONArray(updated).toString()).apply()
        return updated
    }

    @Synchronized
    // 删除持久化的关键词列表，下次读取返回空历史。
    override fun clear() {
        preferences.edit().remove("queries").apply()
    }
}
