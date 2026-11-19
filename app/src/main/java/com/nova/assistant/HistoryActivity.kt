package com.nova.assistant

import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.nova.assistant.ui.HistoryAdapter
import kotlinx.coroutines.runBlocking

/** История задач: что просили и чем закончилось. */
class HistoryActivity : AppCompatActivity() {

    private val history get() = NovaApp.history!!
    private lateinit var adapter: HistoryAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)

        val list = findViewById<RecyclerView>(R.id.list)
        list.layoutManager = LinearLayoutManager(this)
        adapter = HistoryAdapter()
        list.adapter = adapter

        findViewById<Button>(R.id.btnClear).setOnClickListener {
            Thread { runBlocking { history.clear() }; reload() }.start()
        }
        reload()
    }

    private fun reload() {
        Thread {
            val items = runBlocking { history.recent() }
            runOnUiThread { adapter.submit(items) }
        }.start()
    }
}
