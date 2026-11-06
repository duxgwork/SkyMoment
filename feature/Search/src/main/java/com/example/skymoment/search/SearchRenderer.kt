package com.example.skymoment.search

import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.inputmethod.EditorInfo
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.skymoment.domain.Location
import com.example.skymoment.search.databinding.ActivitySearchBinding
import com.example.skymoment.search.databinding.ItemSearchHistoryBinding

/** 控件、监听器和列表渲染集中管理，搜索请求和历史选择由 ViewModel 处理。 */
internal class SearchRenderer(
    private val views: ActivitySearchBinding,
    initialQuery: String,
    queryChanged: (String) -> Unit,
    submit: () -> Unit,
    selectLocation: (Location) -> Unit,
    private val selectHistory: (String) -> Unit,
    clearHistory: () -> Unit,
    back: () -> Unit,
    private val retry: () -> Unit,
) {
    private val adapter = SearchResultAdapter(selectLocation)
    private var renderedHistory: List<String> = emptyList()
    private val watcher: TextWatcher

    init {
        views.searchResults.layoutManager = LinearLayoutManager(views.root.context)
        views.searchResults.adapter = adapter
        views.searchQuery.setText(initialQuery)
        watcher = views.searchQuery.doAfterTextChanged { queryChanged(it?.toString().orEmpty()) }
        views.searchQuery.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_SEARCH) {
                submit()
                true
            } else false
        }
        views.clearSearchHistory.setOnClickListener { clearHistory() }
        views.searchBack.setOnClickListener { back() }
    }

    // 将状态映射到控件内容与可见性，不发起业务数据请求。
    fun render(state: SearchUiState) {
        // 状态回填不触发输入监听，历史标签和旋转恢复不会额外发起查询。
        if (views.searchQuery.text.toString() != state.query) {
            views.searchQuery.removeTextChangedListener(watcher)
            views.searchQuery.setText(state.query)
            views.searchQuery.setSelection(state.query.length)
            views.searchQuery.addTextChangedListener(watcher)
        }
        views.searchStatus.renderSearch(state, retry)
        views.searchResults.isVisible = state.phase == SearchPhase.CONTENT
        adapter.submitList(state.locations)
        views.searchHistorySection.isVisible = state.showHistory
        if (renderedHistory == state.history) return
        renderedHistory = state.history
        views.searchHistoryTags.removeAllViews()
        state.history.forEach { query ->
            val chip = ItemSearchHistoryBinding.inflate(
                LayoutInflater.from(views.root.context), views.searchHistoryTags, false
            ).root
            chip.text = query
            chip.setOnClickListener { selectHistory(query) }
            views.searchHistoryTags.addView(chip)
        }
    }

    // 清除当前视图上的回调与适配器引用，避免销毁后持有旧页面。
    fun clear() {
        views.searchQuery.removeTextChangedListener(watcher)
        views.searchResults.adapter = null
        views.searchQuery.setOnEditorActionListener(null)
        views.clearSearchHistory.setOnClickListener(null)
        views.searchBack.setOnClickListener(null)
        views.searchHistoryTags.removeAllViews()
    }
}
