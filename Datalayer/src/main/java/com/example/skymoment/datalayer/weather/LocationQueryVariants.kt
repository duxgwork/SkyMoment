package com.example.skymoment.datalayer.weather

import com.ibm.icu.text.Transliterator

/**
 * 为中文地点生成原词、去后缀和拼音变体，提高候选召回。
 */
class LocationQueryVariants(
    // 允许注入拼音转换器，生产环境使用 ICU 转写。
    private val transliterate: (String) -> String = {
        // 地名中的“都”读 dū；通用 Han-Latin 会把“新都”转为 xindou。
        // 这是读音规则，不维护地点 ID、坐标或城市数据库。
        Transliterator.getInstance("Han-Latin; NFD; [:Nonspacing Mark:] Remove; NFC; Lower")
            // 将地名中的都预处理为 du，再执行其余文字转写。
            .transliterate(it.replace("都", "du"))
            // 删除拼音空白，形成供应商可检索的连续词。
            .replace(Regex("\\s+"), "")
    },
) {
    /*
     * 保留原词并生成去行政后缀和拼音变体，同时保留附加地区限定。
     */
    fun generateVariants(input: String): List<String> {
        // 统一首尾空白及中英文逗号写法。
        val raw = input.trim().replace('，', ',')
        // 输入不足两个码点时不生成网络查询。
        if (raw.codePointCount(0, raw.length) < 2) return emptyList()
        // 不含目标汉字范围时直接保留原查询。
        if (raw.none { it in '\u3400'..'\u9FFF' }) return listOf(raw)
        // 最多拆分一次，区分地名和地区限定。
        val parts = raw.split(',', limit = 2)
        // 提取需要去后缀及转拼音的主体名称。
        val originalName = parts[0].trim()
        // 按长后缀优先的顺序识别行政名称尾部。
        val suffix = listOf(
            // 先匹配自治县，避免仅去掉末尾的县。
            "自治县",
            // 先匹配自治旗这一完整后缀。
            "自治旗",
            // 先匹配新区，避免仅去掉末尾的区。
            "新区",
            // 兼容普通县名后缀。
            "县",
            // 兼容普通区名后缀。
            "区",
            // 兼容普通市名后缀。
            "市"
            // 只采用首个匹配的行政后缀。
        ).firstOrNull { originalName.endsWith(it) }
        // 准备移除后缀后的检索名称。
        val shortened =
            // 仅在缩短结果仍至少两字时使用，否则保留原名称。
            suffix?.let { originalName.removeSuffix(it) }?.takeIf { it.length >= 2 } ?: originalName
        // 保留逗号后的地区限定并清理其外围空白。
        val qualifier = parts.getOrNull(1)?.let { ",${it.trim()}" }.orEmpty()
        // 返回原词、简化名和拼音变体，并去除完全相同的查询。
        return listOf(raw, shortened + qualifier, transliterate(shortened) + qualifier).distinct()
    }
}
