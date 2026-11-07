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

/**
 * 控件、监听器和列表渲染集中管理，搜索请求和历史选择由 ViewModel 处理。
 */
internal class SearchRenderer(
    // 持有当前搜索布局的 Binding。
    private val views: ActivitySearchBinding,
    // 接收状态机已有输入，供页面重建回填。
    initialQuery: String,
    // 把文本编辑事件上送状态机。
    queryChanged: (String) -> Unit,
    // 把键盘搜索操作上送状态机。
    submit: () -> Unit,
    // 把地点点击交给页面进行选择。
    selectLocation: (Location) -> Unit,
    // 保存历史词点击回调，供动态标签使用。
    private val selectHistory: (String) -> Unit,
    // 接收清空历史的操作。
    clearHistory: () -> Unit,
    // 接收页面返回操作。
    back: () -> Unit,
    // 保存状态面板使用的重试回调。
    private val retry: () -> Unit,
) {
    // 创建按地点身份比较差异的列表适配器。
    private val adapter = SearchResultAdapter(selectLocation)

    // 记录已渲染历史，避免每次状态更新都重建标签。
    private var renderedHistory: List<String> = emptyList()

    // 保存文本监听句柄，以便回填和销毁时移除。
    private val watcher: TextWatcher

    init {
        // 使用纵向列表展示地点候选。
        views.searchResults.layoutManager = LinearLayoutManager(views.root.context)
        // 把候选适配器连接到当前 RecyclerView。
        views.searchResults.adapter = adapter
        // 先回填初始输入，避免触发一次无意义搜索。
        views.searchQuery.setText(initialQuery)
        // 之后监听真实编辑并转交字符串形式的查询。
        watcher = views.searchQuery.doAfterTextChanged { queryChanged(it?.toString().orEmpty()) }
        // 监听输入法提交动作。
        views.searchQuery.setOnEditorActionListener { _, action, _ ->
            // 仅消费键盘明确的搜索操作。
            if (action == EditorInfo.IME_ACTION_SEARCH) {
                // 触发立即提交而非等待文本防抖。
                submit()
                // 告知输入法该搜索动作已处理。
                true
                // 其余编辑动作交由默认行为处理。
            } else false
        }
        // 绑定清空历史按钮。
        views.clearSearchHistory.setOnClickListener { clearHistory() }
        // 绑定页面返回按钮。
        views.searchBack.setOnClickListener { back() }
    }

    /*
     * 将状态映射到控件内容与可见性，不发起业务数据请求。
     */
    fun render(state: SearchUiState) {
        // 状态回填不触发输入监听，历史标签和旋转恢复不会额外发起查询。
        if (views.searchQuery.text.toString() != state.query) {
            // 回填前临时移除监听，避免把渲染当作用户输入。
            views.searchQuery.removeTextChangedListener(watcher)
            // 同步状态机中的查询文本。
            views.searchQuery.setText(state.query)
            // 把光标放到回填内容末尾。
            views.searchQuery.setSelection(state.query.length)
            // 恢复真实输入监听，继续接收用户编辑。
            views.searchQuery.addTextChangedListener(watcher)
        }
        // 按搜索阶段显示加载、过短、空结果或具体错误。
        views.searchStatus.renderSearch(state, retry)
        // 只有有效内容阶段展示候选列表。
        views.searchResults.isVisible = state.phase == SearchPhase.CONTENT
        // 提交当前候选快照，由差异计算更新列表。
        adapter.submitList(state.locations)
        // 仅空白输入且有历史时展示历史区域。
        views.searchHistorySection.isVisible = state.showHistory
        // 历史内容没变时复用已有标签。
        if (renderedHistory == state.history) return
        // 记录本轮历史快照用于后续去重。
        renderedHistory = state.history
        // 先移除已不再适用的旧标签。
        views.searchHistoryTags.removeAllViews()
        // 按历史顺序逐个创建可点击标签。
        state.history.forEach { query ->
            // 为每个搜索词加载统一的历史项布局。
            val chip = ItemSearchHistoryBinding.inflate(
                // 使用页面上下文和父容器生成正确样式与布局参数。
                LayoutInflater.from(views.root.context), views.searchHistoryTags, false
                // 取历史项根控件作为标签。
            ).root
            // 标签显示该条历史关键词。
            chip.text = query
            // 点击标签时重新搜索对应词条。
            chip.setOnClickListener { selectHistory(query) }
            // 把标签加入当前历史区域。
            views.searchHistoryTags.addView(chip)
        }
    }

    /*
     * 清除当前视图上的回调与适配器引用，避免销毁后持有旧页面。
     */
    fun clear() {
        // 销毁时停止文本回调，释放页面操作引用。
        views.searchQuery.removeTextChangedListener(watcher)
        // 解除列表适配器，避免旧视图继续持有候选回调。
        views.searchResults.adapter = null
        // 解除输入法动作监听。
        views.searchQuery.setOnEditorActionListener(null)
        // 解除清空历史按钮监听。
        views.clearSearchHistory.setOnClickListener(null)
        // 解除返回按钮监听。
        views.searchBack.setOnClickListener(null)
        // 移除动态历史标签及其点击回调。
        views.searchHistoryTags.removeAllViews()
    }
}
