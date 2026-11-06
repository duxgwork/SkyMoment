package com.example.skymoment.framework

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.util.Locale

@RunWith(Parameterized::class)
/** 覆盖 LocaleResolver 的正常流程、恢复行为与边界条件。 */
class LocaleResolverTest(
    private val languages: String,
    private val expected: UiLanguage,
    private val apiCode: String,
) {
    @Test
    // 验证系统首选语言同时决定资源 Locale 和 API 语言。
    fun firstSystemLocaleDeterminesResourcesAndApiLanguage() {
        val locales = languages.split(',').filter { it.isNotEmpty() }.map(Locale::forLanguageTag)
        val result = LocaleResolver.resolve(locales)
        assertEquals(expected, result)
        assertEquals(apiCode, result.apiCode)
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0} -> {1}")
        // 提供覆盖语言、脚本和地区优先级的参数化测试数据。
        fun cases() = listOf(
            arrayOf<Any>("zh-CN", UiLanguage.SIMPLIFIED_CHINESE, "zh"),
            arrayOf<Any>("zh-SG", UiLanguage.SIMPLIFIED_CHINESE, "zh"),
            arrayOf<Any>("zh-TW", UiLanguage.TRADITIONAL_CHINESE, "zh"),
            arrayOf<Any>("zh-HK", UiLanguage.TRADITIONAL_CHINESE, "zh"),
            arrayOf<Any>("zh-MO", UiLanguage.TRADITIONAL_CHINESE, "zh"),
            arrayOf<Any>("zh-Hans-TW", UiLanguage.SIMPLIFIED_CHINESE, "zh"),
            arrayOf<Any>("zh-Hant-CN", UiLanguage.TRADITIONAL_CHINESE, "zh"),
            arrayOf<Any>("en-US", UiLanguage.ENGLISH, "en"),
            arrayOf<Any>("fr-FR,zh-CN", UiLanguage.ENGLISH, "en"),
            arrayOf<Any>("zh-HK,en-US", UiLanguage.TRADITIONAL_CHINESE, "zh"),
            arrayOf<Any>("", UiLanguage.ENGLISH, "en"),
        )
    }
}
