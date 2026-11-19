package com.nova.assistant

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.nova.assistant.camera.CameraAssistantActivity
import com.nova.assistant.permission.PermissionManager
import com.nova.assistant.service.FloatingService

/**
 * Главный экран: разрешения, запуск помощника, голосовая и текстовая команда,
 * быстрый доступ к разделам.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var infoText: TextView
    private lateinit var input: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        infoText = findViewById(R.id.infoText)
        input = findViewById(R.id.input)

        findViewById<Button>(R.id.btnOverlay).setOnClickListener {
            if (!PermissionManager.canDrawOverlays(this)) {
                startActivity(PermissionManager.overlayIntent(this))
            } else {
                Toast.makeText(this, "Показ поверх приложений уже разрешён", Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<Button>(R.id.btnPermissions).setOnClickListener { requestRuntimePermissions() }

        findViewById<Button>(R.id.btnAccessibility).setOnClickListener {
            startActivity(PermissionManager.accessibilityIntent())
        }

        findViewById<Button>(R.id.btnStart).setOnClickListener {
            if (!PermissionManager.canDrawOverlays(this)) {
                Toast.makeText(this, "Сначала разрешите показ поверх приложений", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            val i = Intent(this, FloatingService::class.java).setAction(FloatingService.ACTION_START)
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
            Toast.makeText(this, "NOVA запущена", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.btnStop).setOnClickListener {
            startService(Intent(this, FloatingService::class.java).setAction(FloatingService.ACTION_STOP))
        }

        findViewById<Button>(R.id.btnListen).setOnClickListener {
            startService(Intent(this, FloatingService::class.java).setAction(FloatingService.ACTION_LISTEN))
        }

        findViewById<Button>(R.id.btnSend).setOnClickListener {
            val t = input.text.toString().trim()
            if (t.isNotEmpty()) {
                startService(
                    Intent(this, FloatingService::class.java)
                        .setAction(FloatingService.ACTION_TEXT)
                        .putExtra("text", t)
                )
                input.text.clear()
            }
        }

        findViewById<Button>(R.id.btnNotes).setOnClickListener { startActivity(Intent(this, NotesActivity::class.java)) }
        findViewById<Button>(R.id.btnHistory).setOnClickListener { startActivity(Intent(this, HistoryActivity::class.java)) }
        findViewById<Button>(R.id.btnApps).setOnClickListener { startActivity(Intent(this, AppsActivity::class.java)) }
        findViewById<Button>(R.id.btnSettings).setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        findViewById<Button>(R.id.btnCamera).setOnClickListener { startActivity(Intent(this, CameraAssistantActivity::class.java)) }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun requestRuntimePermissions() {
        val perms = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.CAMERA
        )
        if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
        requestPermissions(perms.toTypedArray(), 100)
    }

    private fun refreshStatus() {
        val sb = StringBuilder()
        sb.append("Показ поверх приложений: ").append(if (PermissionManager.canDrawOverlays(this)) "разрешено" else "нет").append('\n')
        sb.append("Служба доступа к экрану: ").append(if (PermissionManager.isAccessibilityEnabled(this)) "включена" else "выключена").append('\n')
        sb.append("Микрофон: ").append(if (PermissionManager.hasMic(this)) "разрешён" else "нет").append('\n')
        sb.append("Контакты: ").append(if (PermissionManager.hasContacts(this)) "разрешены" else "нет").append('\n')
        sb.append("Камера: ").append(if (PermissionManager.hasCamera(this)) "разрешена" else "нет")
        statusText.text = sb.toString()

        val model = java.io.File(filesDir, "vosk-model").isDirectory
        infoText.text = "Офлайн-распознавание (Vosk): " + (if (model) "модель найдена" else "модель не установлена — используется системное распознавание") +
            "\nГолос и память работают локально, интернет нужен только для поиска в сети."
    }
}
