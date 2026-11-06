package com.example.skymoment.datalayer.weather

import com.ibm.icu.text.Transliterator

/** 为中文地点生成原词、去后缀和拼音变体，提高候选召回。 */
class LocationQueries(
    private val transliterate: (String) -> String = {
        // 地名中的“都”读 dū；通用 Han-Latin 会把“新都”转为 xindou。
        // 这是读音规则，不维护地点 ID、坐标或城市数据库。
        Transliterator.getInstance("Han-Latin; NFD; [:Nonspacing Mark:] Remove; NFC; Lower")
            .transliterate(it.replace("都", "du"))
            .replace(Regex("\\s+"), "")
    },
) {
    // 保留原词并生成去行政后缀和拼音变体，同时保留附加地区限定。
    fun variants(input: String): List<String> {
        val raw = input.trim().replace('，', ',')
        if (raw.codePointCount(0, raw.length) < 2) return emptyList()
        if (raw.none { it in '\u3400'..'\u9FFF' }) return listOf(raw)
        val parts = raw.split(',', limit = 2)
        val originalName = parts[0].trim()
        val suffix = listOf(
            "自治县",
            "自治旗",
            "新区",
            "县",
            "区",
            "市"
        ).firstOrNull { originalName.endsWith(it) }
        val shortened =
            suffix?.let { originalName.removeSuffix(it) }?.takeIf { it.length >= 2 } ?: originalName
        val qualifier = parts.getOrNull(1)?.let { ",${it.trim()}" }.orEmpty()
        return listOf(raw, shortened + qualifier, transliterate(shortened) + qualifier).distinct()
    }
}
