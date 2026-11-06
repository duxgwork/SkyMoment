package com.example.skymoment.framework

import java.util.Locale

/** 绑定资源 Locale 和接口语言码，确保界面与数据请求语言一致。 */
enum class UiLanguage(val locale: Locale, val apiCode: String) {
    ENGLISH(Locale.ENGLISH, "en"),
    SIMPLIFIED_CHINESE(Locale.forLanguageTag("zh-Hans"), "zh"),
    TRADITIONAL_CHINESE(Locale.forLanguageTag("zh-Hant"), "zh"),
}

/** 将系统首选语言归一为英文、简体或繁体中文。 */
object LocaleResolver {
    // 仅根据系统首选语言选择简体、繁体或英文，脚本优先于地区。
    fun resolve(preferredLocales: List<Locale>): UiLanguage {
        val first = preferredLocales.firstOrNull() ?: return UiLanguage.ENGLISH
        if (first.language != "zh") return UiLanguage.ENGLISH
        return when (first.script) {
            "Hans" -> UiLanguage.SIMPLIFIED_CHINESE
            "Hant" -> UiLanguage.TRADITIONAL_CHINESE
            else -> if (first.country in setOf("TW", "HK", "MO")) {
                UiLanguage.TRADITIONAL_CHINESE
            } else {
                UiLanguage.SIMPLIFIED_CHINESE
            }
        }
    }
}
