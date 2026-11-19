package com.nova.assistant.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.nova.assistant.R
import com.nova.assistant.core.TaskRecord
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HistoryAdapter : RecyclerView.Adapter<HistoryAdapter.VH>() {

    private val items = mutableListOf<TaskRecord>()
    private val fmt = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale("ru"))

    fun submit(list: List<TaskRecord>) {
        items.clear(); items.addAll(list); notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_history, parent, false)
        return VH(v)
    }

    override fun getItemCount(): Int = items.size
    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        private val goal: TextView = view.findViewById(R.id.goal)
        private val status: TextView = view.findViewById(R.id.status)
        private val report: TextView = view.findViewById(R.id.report)

        fun bind(r: TaskRecord) {
            goal.text = r.goal
            status.text = "${r.status} · ${fmt.format(Date(r.createdAt))}"
            report.text = r.report
        }
    }
}
