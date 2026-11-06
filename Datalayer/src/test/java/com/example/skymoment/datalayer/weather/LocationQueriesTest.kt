package com.example.skymoment.datalayer.weather

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 覆盖 LocationQueries 的正常流程、恢复行为与边界条件。 */
class LocationQueriesTest {
    @Test
    // 验证打包 ICU 可在不依赖 Android 平台转写 API 的环境下工作。
    fun bundledIcuWorksWithoutAndroidPlatformApis() {
        val real = LocationQueries()
        assertEquals(listOf("海淀区", "海淀", "haidian"), real.variants("海淀区"))
        assertEquals(listOf("德清县", "德清", "deqing"), real.variants("德清县"))
        assertEquals(listOf("昆山市", "昆山", "kunshan"), real.variants("昆山市"))
        assertEquals(listOf("新都区", "新都", "xindu"), real.variants("新都区"))
    }

    private val queries = LocationQueries { if (it == "德清") "deqing" else "kunshan" }
    @Test
    // 验证即使原词可能命中，查询变体仍包含拼音补查。
    fun variantsIncludePinyinEvenWhenOriginalMayHaveResults() {
        assertEquals(listOf("德清县", "德清", "deqing"), queries.variants(" 德清县 "))
        assertEquals(listOf("昆山", "kunshan"), queries.variants("昆山"))
        assertEquals(listOf("London"), queries.variants(" London "))
        assertTrue(queries.variants("德").isEmpty())
        assertEquals(
            listOf("德清县,浙江", "德清,浙江", "deqing,浙江"),
            queries.variants("德清县，浙江")
        )
    }
}
