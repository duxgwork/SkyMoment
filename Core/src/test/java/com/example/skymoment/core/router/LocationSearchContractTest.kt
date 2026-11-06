package com.example.skymoment.core.router

import android.app.Activity
import android.content.Intent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 结果契约只接收成功的非空会话，取消与异常数据不能改变当前地点。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LocationSearchContractTest {
    private val contract = LocationSearchContract()

    @Test fun acceptsOnlySuccessfulNonBlankTokens() {
        val intent = Intent().putExtra(NavigationExtras.SESSION_TOKEN, "opaque-session")
        assertEquals("opaque-session", contract.parseResult(Activity.RESULT_OK, intent))
        assertNull(contract.parseResult(Activity.RESULT_CANCELED, intent))
        assertNull(contract.parseResult(123, intent))
    }

    @Test fun missingOrBlankResultIsCancellation() {
        assertNull(contract.parseResult(Activity.RESULT_OK, null))
        assertNull(contract.parseResult(Activity.RESULT_OK, Intent()))
        listOf("", " ", "\n\t").forEach {
            assertNull(contract.parseResult(Activity.RESULT_OK, Intent().putExtra(NavigationExtras.SESSION_TOKEN, it)))
        }
    }

    @Test fun locationExtrasCannotSubstituteForSessionToken() {
        val intent = Intent().putExtra("latitude", 31.2).putExtra("longitude", 121.5)
        assertNull(contract.parseResult(Activity.RESULT_OK, intent))
    }
}
