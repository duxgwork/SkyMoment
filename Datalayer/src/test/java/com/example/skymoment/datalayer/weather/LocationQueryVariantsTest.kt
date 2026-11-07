package com.example.skymoment.datalayer.weather

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 覆盖 LocationQueryVariants 的正常流程、恢复行为与边界条件。 */
class LocationQueryVariantsTest {
    @Test
    // 验证打包 ICU 可在不依赖 Android 平台转写 API 的环境下工作。
    fun bundledIcuWorksWithoutAndroidPlatformApis() {
        val real = LocationQueryVariants()
        assertEquals(listOf("海淀区", "海淀", "haidian"), real.generateVariants("海淀区"))
        assertEquals(listOf("德清县", "德清", "deqing"), real.generateVariants("德清县"))
        assertEquals(listOf("昆山市", "昆山", "kunshan"), real.generateVariants("昆山市"))
        assertEquals(listOf("新都区", "新都", "xindu"), real.generateVariants("新都区"))
    }

    private val queries = LocationQueryVariants { if (it == "德清") "deqing" else "kunshan" }
    @Test
    // 验证即使原词可能命中，查询变体仍包含拼音补查。
    fun variantsIncludePinyinEvenWhenOriginalMayHaveResults() {
        assertEquals(listOf("德清县", "德清", "deqing"), queries.generateVariants(" 德清县 "))
        assertEquals(listOf("昆山", "kunshan"), queries.generateVariants("昆山"))
        assertEquals(listOf("London"), queries.generateVariants(" London "))
        assertTrue(queries.generateVariants("德").isEmpty())
        assertEquals(
            listOf("德清县,浙江", "德清,浙江", "deqing,浙江"),
            queries.generateVariants("德清县，浙江")
        )
    }
}
