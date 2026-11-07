package com.example.skymoment.datalayer.weather

import com.example.skymoment.domain.CurrentWeather
import com.example.skymoment.domain.Location
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/** 覆盖 InMemoryLocationSessionRepository 的正常流程、恢复行为与边界条件。 */
class InMemoryLocationSessionRepositoryTest {
    @Test
    // 验证新选择、清空和新进程均使旧会话 token 失效。
    fun newSelectionClearAndNewProcessInvalidatePreviousToken() {
        val repository = InMemoryLocationSessionRepository()
        val first = Location(
            "test",
            "synthetic-1",
            "示例区",
            emptyList(),
            1.0,
            2.0,
            ZoneId.of("Asia/Shanghai")
        )
        val second = first.copy(id = "synthetic-2", name = "另一示例区")
        val current = CurrentWeather(12.0, null, null, null, null, Instant.parse("2026-11-06T00:00:00Z"), emptyList())
        val token = repository.createSession(first, current)
        assertEquals(first, repository.findLocation(token))
        assertEquals(current, repository.findInitialWeather(token))
        val newToken = repository.createSession(second)
        assertNotEquals(token, newToken)
        assertNull(repository.findLocation(token))
        assertNull(repository.findInitialWeather(token))
        assertNull(repository.findInitialWeather(newToken))
        assertEquals(second, repository.findLocation(newToken))
        assertNull(InMemoryLocationSessionRepository().findLocation(newToken))
        repository.clear()
        assertNull(repository.findLocation(newToken))
    }
}
