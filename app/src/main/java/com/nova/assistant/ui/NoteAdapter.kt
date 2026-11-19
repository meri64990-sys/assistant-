package com.nova.assistant.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.nova.assistant.R
import com.nova.assistant.core.Note

/** Список заметок. */
class NoteAdapter(
    private val onFavorite: (Note) -> Unit,
    private val onDelete: (Note) -> Unit
) : RecyclerView.Adapter<NoteAdapter.VH>() {

    private val items = mutableListOf<Note>()

    fun submit(list: List<Note>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_note, parent, false)
        return VH(v)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        private val title: TextView = view.findViewById(R.id.title)
        private val content: TextView = view.findViewById(R.id.content)
        private val category: TextView = view.findViewById(R.id.category)
        private val fav: Button = view.findViewById(R.id.btnFav)
        private val del: Button = view.findViewById(R.id.btnDelete)

        fun bind(n: Note) {
            title.text = n.title
            content.text = n.content
            category.text = n.category + " · " + android.text.format.DateFormat.format("dd.MM.yyyy HH:mm", n.createdAt)
            fav.text = if (n.favorite) "★" else "☆"
            fav.setOnClickListener { onFavorite(n) }
            del.setOnClickListener { onDelete(n) }
        }
    }
}
