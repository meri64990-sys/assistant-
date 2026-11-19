package com.nova.assistant

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.nova.assistant.memory.MemoryRepository
import com.nova.assistant.notes.NotesManager
import com.nova.assistant.settings.SettingsManager
import com.nova.assistant.tasks.TaskHistory
import kotlinx.coroutines.runBlocking

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        supportFragmentManager.beginTransaction()
            .replace(R.id.container, PrefsFragment())
            .commit()
    }

    class PrefsFragment : PreferenceFragmentCompat() {

        private val settings: SettingsManager get() = NovaApp.settings!!
        private val notes: NotesManager get() = NovaApp.notes!!
        private val history: TaskHistory get() = NovaApp.history!!
        private val memory: MemoryRepository get() = NovaApp.memory!!

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            // Та же база, что и у SettingsManager, чтобы настройки реально применялись.
            preferenceManager.sharedPreferencesName = "nova_settings"
            setPreferencesFromResource(R.xml.preferences, rootKey)

            findPreference<Preference>("clear_notes")?.setOnPreferenceClickListener {
                Thread { runBlocking { notes.deleteAll() } }.start()
                Toast.makeText(requireContext(), "Заметки удалены", Toast.LENGTH_SHORT).show()
                true
            }
            findPreference<Preference>("clear_history")?.setOnPreferenceClickListener {
                Thread { runBlocking { history.clear() } }.start()
                Toast.makeText(requireContext(), "История очищена", Toast.LENGTH_SHORT).show()
                true
            }
            findPreference<Preference>("clear_memory")?.setOnPreferenceClickListener {
                Thread { runBlocking { memory.clear() } }.start()
                Toast.makeText(requireContext(), "Память очищена", Toast.LENGTH_SHORT).show()
                true
            }
        }

        override fun onResume() {
            super.onResume()
            // Привязываем значения к менеджеру настроек.
            preferenceManager.sharedPreferences?.registerOnSharedPreferenceChangeListener(listener)
        }

        override fun onPause() {
            super.onPause()
            preferenceManager.sharedPreferences?.unregisterOnSharedPreferenceChangeListener(listener)
        }

        private val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { sp, key ->
            when (key) {
                "voice" -> settings.voiceEnabled = sp.getBoolean(key, true)
                "always" -> settings.alwaysListening = sp.getBoolean(key, true)
                "wake" -> settings.wakeWordEnabled = sp.getBoolean(key, true)
                "visible" -> settings.characterVisible = sp.getBoolean(key, true)
                "overlay" -> settings.overlayEnabled = sp.getBoolean(key, true)
                "offline_stt" -> settings.offlineStt = sp.getBoolean(key, true)
                "confirm" -> settings.confirmSensitive = sp.getBoolean(key, true)
                "history" -> settings.saveHistory = sp.getBoolean(key, true)
                "privacy" -> settings.privacyFilter = sp.getBoolean(key, true)
                "debug" -> settings.debugLog = sp.getBoolean(key, false)
                "mode" -> settings.performanceMode = sp.getString(key, "standard") ?: "standard"
                "scale" -> settings.characterScale = (sp.getInt(key, 10) / 10f)
                "volume" -> settings.volume = (sp.getInt(key, 100) / 100f)
                "rate" -> settings.speechRate = (sp.getInt(key, 10) / 10f)
                "sens" -> settings.micSensitivity = sp.getInt(key, 60)
            }
        }
    }
}
