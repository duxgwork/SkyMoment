package com.example.skymoment.datalayer.weather

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tencent.mmkv.MMKV
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** 在 Android 上运行真实 MMKV JNI，验证本地文件读写。 */
@RunWith(AndroidJUnit4::class)
class MmkvSearchHistoryRepositoryTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val storage get() = requireNotNull(MMKV.mmkvWithID("search_history"))
    private val repository get() = MmkvSearchHistoryRepository(context)

    @Before fun resetStorage() {
        MMKV.initialize(context)
        storage.clearAll()
    }

    @After fun cleanStorage() {
        storage.clearAll()
        storage.close()
    }

    @Test fun persistsAcrossInstancesDeduplicatesAndMovesReuseToFront() {
        repository.recordQuery(" London ")
        repository.recordQuery("上海")
        assertEquals(listOf("LONDON", "上海"), repository.recordQuery("LONDON"))
        assertEquals(listOf("LONDON", "上海"), repository.getRecentQueries())
    }

    @Test fun persistsAfterStorageIsClosedAndReopened() {
        repository.recordQuery("上海")
        storage.sync()
        storage.close()
        assertEquals(listOf("上海"), repository.getRecentQueries())
    }

    @Test fun capsHistoryAndCountsUnicodeCodePoints() {
        (1..12).forEach { repository.recordQuery("City $it") }
        val expected = (12 downTo 3).map { "City $it" }
        assertEquals(expected, repository.getRecentQueries())
        listOf("", " ", "区", "😀").forEach { assertEquals(expected, repository.recordQuery(it)) }
        assertEquals("😀😀", repository.recordQuery("😀😀").first())
    }

    @Test fun corruptedStorageFallsBackAndCanBeReplaced() {
        assertTrue(storage.encode("queries", "broken JSON"))
        assertTrue(repository.getRecentQueries().isEmpty())
        assertEquals(listOf("上海"), repository.recordQuery("上海"))
    }

    @Test fun readingCleansDataAndClearPersistsAfterReopening() {
        assertTrue(storage.encode("queries", "[\" Paris \",\"paris\",\"x\",\"\",\"上海\"]"))
        assertEquals(listOf("Paris", "上海"), repository.getRecentQueries())
        repository.clear()
        storage.sync()
        storage.close()
        assertTrue(repository.getRecentQueries().isEmpty())
    }
}
