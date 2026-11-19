package com.nova.assistant.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.nova.assistant.R
import com.nova.assistant.permission.PermissionManager

class AppAdapter(
    private val onOpen: (PermissionManager.AppInfo) -> Unit
) : RecyclerView.Adapter<AppAdapter.VH>() {

    private val items = mutableListOf<PermissionManager.AppInfo>()

    fun submit(list: List<PermissionManager.AppInfo>) {
        items.clear(); items.addAll(list); notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false)
        return VH(v)
    }

    override fun getItemCount(): Int = items.size
    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        private val label: TextView = view.findViewById(R.id.label)
        private val open: Button = view.findViewById(R.id.btnOpen)

        fun bind(a: PermissionManager.AppInfo) {
            label.text = a.label
            open.setOnClickListener { onOpen(a) }
        }
    }
}
