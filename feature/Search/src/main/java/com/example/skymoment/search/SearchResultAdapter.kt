package com.example.skymoment.search

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.skymoment.domain.Location
import com.example.skymoment.search.databinding.ItemLocationBinding

/**
 * 展示候选地点与行政路径，用户点击后回调显式选择。
 */
class SearchResultAdapter(
    private val select: (Location) -> Unit
) : ListAdapter<Location, SearchResultAdapter.ViewHolder>(Diff) {// 用 ListAdapter 按差异更新候选，避免整表刷新。
    /**
     * 仅持有当前列表项的 ViewBinding，由 RecyclerView 管理复用生命周期。
     */
    class ViewHolder(val binding: ItemLocationBinding) : RecyclerView.ViewHolder(binding.root)

    /*
     * 创建列表项绑定；由 RecyclerView 负责把根视图附加到父容器。
     */
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(
        // 加载单个地点行布局，保持由 RecyclerView 负责附着。
        ItemLocationBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )

    /*
     * 将当前位置的列表数据和操作回调绑定到复用的视图。
     */
    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        // 读取当前位置对应的地点模型。
        val location = getItem(position)
        // 显示供应商返回的地点名称。
        holder.binding.locationName.text = location.name
        // 以分隔符组合行政路径，帮助用户识别同名地点。
        holder.binding.locationPath.text = location.administrativePath.joinToString(" · ")
        // 点击时交付这一行实际绑定的地点。
        holder.binding.root.setOnClickListener { select(location) }
    }

    /*
     * 清除列表项点击监听，避免复用后仍引用旧数据。
     */
    override fun onViewRecycled(holder: ViewHolder) {
        // 回收行视图时移除旧地点点击引用。
        holder.binding.root.setOnClickListener(null)
    }

    /**
     * 定义稳定条目标识与完整内容比较，减少列表无意义的重绑。
     */
    private object Diff : DiffUtil.ItemCallback<Location>() {
        /*
         * 比较稳定业务标识，判断列表前后是否为同一项。
         */
        override fun areItemsTheSame(old: Location, new: Location) =
            // 供应商与 ID 同时一致才视为同一候选。
            old.provider == new.provider && old.id == new.id

        /*
         * 比较完整展示数据，使标签、数值等变化触发重新绑定。
         */
        override fun areContentsTheSame(old: Location, new: Location) = old == new
    }
}
