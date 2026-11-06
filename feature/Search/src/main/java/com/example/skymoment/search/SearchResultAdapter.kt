package com.example.skymoment.search

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.skymoment.domain.Location
import com.example.skymoment.search.databinding.ItemLocationBinding

/** 展示候选地点与行政路径，用户点击后回调显式选择。 */
class SearchResultAdapter(private val select: (Location) -> Unit) :
    ListAdapter<Location, SearchResultAdapter.ViewHolder>(Diff) {
    /** 仅持有当前列表项的 ViewBinding，由 RecyclerView 管理复用生命周期。 */
    class ViewHolder(val binding: ItemLocationBinding) : RecyclerView.ViewHolder(binding.root)

    // 创建列表项绑定；由 RecyclerView 负责把根视图附加到父容器。
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(
        ItemLocationBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )

    // 将当前位置的列表数据和操作回调绑定到复用的视图。
    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val location = getItem(position)
        holder.binding.locationName.text = location.name
        holder.binding.locationPath.text = location.administrativePath.joinToString(" · ")
        holder.binding.root.setOnClickListener { select(location) }
    }

    // 清除列表项点击监听，避免复用后仍引用旧数据。
    override fun onViewRecycled(holder: ViewHolder) {
        holder.binding.root.setOnClickListener(null)
    }

    /** 定义稳定条目标识与完整内容比较，减少列表无意义的重绑。 */
    private object Diff : DiffUtil.ItemCallback<Location>() {
        // 比较稳定业务标识，判断列表前后是否为同一项。
        override fun areItemsTheSame(old: Location, new: Location) =
            old.provider == new.provider && old.id == new.id

        // 比较完整展示数据，使标签、数值等变化触发重新绑定。
        override fun areContentsTheSame(old: Location, new: Location) = old == new
    }
}
