package com.example.skymoment.framework

import java.util.Locale

/**
 * 绑定资源 Locale 和接口语言码，确保界面与数据请求语言一致。
 */
enum class UiLanguage(val locale: Locale, val apiCode: String) {
    // 英文界面对应英文资源和地理接口语言 en。
    ENGLISH(Locale.ENGLISH, "en"),

    // 简体界面使用 Hans 资源，地理接口统一请求 zh。
    SIMPLIFIED_CHINESE(Locale.forLanguageTag("zh-Hans"), "zh"),

    // 繁体界面使用 Hant 资源，地理接口同样请求 zh。
    TRADITIONAL_CHINESE(Locale.forLanguageTag("zh-Hant"), "zh"),
}

/**
 * 将系统首选语言归一为英文、简体或繁体中文。
 */
object LocaleResolver {
    /*
     * 仅根据系统首选语言选择简体、繁体或英文，脚本优先于地区。
     */
    fun resolve(preferredLocales: List<Locale>): UiLanguage {
        // 只按系统首选语言决定界面，无首选时回退英文。
        val first = preferredLocales.firstOrNull() ?: return UiLanguage.ENGLISH
        // 非中文首选语言统一回退到受支持的英文界面。
        if (first.language != "zh") return UiLanguage.ENGLISH
        // 优先用显式脚本标识区分简体与繁体。
        return when (first.script) {
            // Hans 脚本明确采用简体中文。
            "Hans" -> UiLanguage.SIMPLIFIED_CHINESE
            // Hant 脚本明确采用繁体中文。
            "Hant" -> UiLanguage.TRADITIONAL_CHINESE
            // 未指定脚本时按台湾、香港和澳门地区判断繁体。
            else -> if (first.country in setOf("TW", "HK", "MO")) {
                // 对应地区使用繁体资源。
                UiLanguage.TRADITIONAL_CHINESE
            } else {
                // 返回简体中文界面配置。
                UiLanguage.SIMPLIFIED_CHINESE
            }
        }
    }
}
