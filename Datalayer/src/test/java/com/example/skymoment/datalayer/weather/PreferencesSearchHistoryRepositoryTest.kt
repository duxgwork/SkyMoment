package com.example.skymoment.datalayer.weather

import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PreferencesSearchHistoryRepositoryTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val preferences get() = context.getSharedPreferences("search_history", Context.MODE_PRIVATE)
    private val repository get() = PreferencesSearchHistoryRepository(context)

    @Before fun resetStorage() { preferences.edit().clear().commit() }

    @Test fun persistsAcrossInstancesDeduplicatesAndMovesReuseToFront() {
        repository.record(" London ")
        repository.record("上海")
        assertEquals(listOf("LONDON", "上海"), repository.record("LONDON"))
        assertEquals(listOf("LONDON", "上海"), repository.recent())
    }

    @Test fun capsHistoryAndCountsUnicodeCodePoints() {
        (1..12).forEach { repository.record("City $it") }
        val expected = (12 downTo 3).map { "City $it" }
        assertEquals(expected, repository.recent())
        listOf("", " ", "区", "😀").forEach { assertEquals(expected, repository.record(it)) }
        assertEquals("😀😀", repository.record("😀😀").first())
    }

    @Test fun corruptedStorageFallsBackAndCanBeReplaced() {
        preferences.edit().putString("queries", "broken JSON").commit()
        assertTrue(repository.recent().isEmpty())
        assertEquals(listOf("上海"), repository.record("上海"))
    }

    @Test fun readingCleansLegacyDataAndClearPersists() {
        preferences.edit().putString("queries", "[\" Paris \",\"paris\",\"x\",\"\",\"上海\"]").commit()
        assertEquals(listOf("Paris", "上海"), repository.recent())
        repository.clear()
        assertTrue(repository.recent().isEmpty())
    }
}
