package com.nova.assistant

import android.os.Bundle
import android.widget.EditText
import android.widget.Button
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.nova.assistant.core.Note
import com.nova.assistant.ui.NoteAdapter
import kotlinx.coroutines.runBlocking
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Заметки: создание, поиск, избранное, удаление. Данные — локальные. */
class NotesActivity : AppCompatActivity() {

    private val notes get() = NovaApp.notes!!
    private lateinit var adapter: NoteAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_notes)

        val list = findViewById<RecyclerView>(R.id.list)
        list.layoutManager = LinearLayoutManager(this)
        adapter = NoteAdapter(
            onFavorite = { n -> Thread { runBlocking { notes.setFavorite(n, !n.favorite) }; reload() }.start() },
            onDelete = { n -> confirmDelete(n) }
        )
        list.adapter = adapter

        findViewById<EditText>(R.id.search).addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val q = s?.toString()?.trim().orEmpty()
                Thread { reload(q) }.start()
            }
        })

        findViewById<Button>(R.id.btnAdd).setOnClickListener { showEditDialog(null) }
        reload()
    }

    private fun reload(query: String? = null) {
        val list = runBlocking {
            if (query.isNullOrBlank()) notes.all() else notes.search(query)
        }
        runOnUiThread { adapter.submit(list) }
    }

    private fun confirmDelete(n: Note) {
        AlertDialog.Builder(this)
            .setTitle("Удалить заметку?")
            .setMessage(n.title)
            .setPositiveButton("Удалить") { _, _ ->
                Thread { runBlocking { notes.delete(n) }; reload() }.start()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun showEditDialog(existing: Note?) {
        val input = EditText(this).apply {
            hint = "Текст заметки"
            setText(existing?.content ?: "")
        }
        AlertDialog.Builder(this)
            .setTitle(if (existing == null) "Новая заметка" else "Изменить заметку")
            .setView(input)
            .setPositiveButton("Сохранить") { _, _ ->
                val text = input.text.toString().trim()
                if (text.isEmpty()) return@setPositiveButton
                Thread {
                    runBlocking {
                        if (existing == null) notes.create(text) else notes.update(existing, text)
                    }
                    reload()
                }.start()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }
}
