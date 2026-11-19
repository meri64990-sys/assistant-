package com.nova.assistant

import android.os.Bundle
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.nova.assistant.core.FuzzyMatcher
import com.nova.assistant.device.AppLauncher
import com.nova.assistant.ui.AppAdapter

/** Список установленных приложений и запуск их по одному нажатию. */
class AppsActivity : AppCompatActivity() {

    private val launcher by lazy { AppLauncher(this) }
    private lateinit var adapter: AppAdapter
    private var all: List<com.nova.assistant.permission.PermissionManager.AppInfo> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_apps)

        val list = findViewById<RecyclerView>(R.id.list)
        list.layoutManager = LinearLayoutManager(this)
        adapter = AppAdapter { app ->
            launcher.launch(app)
        }
        list.adapter = adapter

        findViewById<EditText>(R.id.search).addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) = filter(s?.toString().orEmpty())
        })

        Thread {
            all = launcher.listApps()
            runOnUiThread { adapter.submit(all) }
        }.start()
    }

    private fun filter(q: String) {
        if (q.isBlank()) { adapter.submit(all); return }
        val n = FuzzyMatcher.normalize(q)
        adapter.submit(all.filter { FuzzyMatcher.score(n, it.label) >= 0.5f })
    }
}
