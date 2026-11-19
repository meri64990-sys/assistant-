package com.nova.assistant.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.nova.assistant.NovaApp
import com.nova.assistant.R
import com.nova.assistant.overlay.CharacterState
import com.nova.assistant.overlay.OverlayController

/**
 * Фоновый сервис NOVA: держит плавающего персонажа, голосовой контур
 * и ядро помощника. Работает, пока пользователь не выключит его.
 */
class FloatingService : Service() {

    companion object {
        const val CHANNEL_ID = "nova_service"
        const val NOTIF_ID = 1

        const val ACTION_START = "com.nova.assistant.START"
        const val ACTION_STOP = "com.nova.assistant.STOP"
        const val ACTION_HIDE = "com.nova.assistant.HIDE"
        const val ACTION_SHOW = "com.nova.assistant.SHOW"
        const val ACTION_LISTEN = "com.nova.assistant.LISTEN"
        const val ACTION_TEXT = "com.nova.assistant.TEXT"

        @Volatile var instance: FloatingService? = null
            private set
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var core: AssistantCore
    private var overlay: OverlayController? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        createChannel()
        startForegroundCompat(buildNotification())

        overlay = OverlayController(this, NovaApp.settings!!, object : OverlayController.Listener {
            override fun onMic() { core.listen() }
            override fun onStop() { core.stopAll() }
            override fun onHide() { overlay?.hide() }
            override fun onSendText(text: String) { core.onVoiceResult(text) }
            override fun onDoubleTap() { core.listen() }
            override fun onLongPress() { overlay?.hide() }
        })

        core = AssistantCore(this, host)
        core.start()
        if (NovaApp.settings!!.characterVisible) overlay?.show()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { core.stopAll(); stopSelf() }
            ACTION_HIDE -> overlay?.hide()
            ACTION_SHOW -> overlay?.show()
            ACTION_LISTEN -> core.listen()
            ACTION_TEXT -> intent.getStringExtra("text")?.let { core.onVoiceResult(it) }
            else -> Unit
        }
        return START_STICKY
    }

    override fun onDestroy() {
        core.destroy()
        overlay?.hide()
        instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ------------------------------------------------------------------ //
    // Хост для ядра
    // ------------------------------------------------------------------ //

    private val host = object : AssistantCore.Host {
        override fun showMessage(text: String) { overlay?.setMessage(text) }
        override fun setState(state: CharacterState) { overlay?.setState(state) }
        override fun speak(text: String) { core.voiceEngine.speak(text) }
        override fun showProgress(text: String) { overlay?.setMessage(text) }
        override fun onTaskFinished(report: String) { overlay?.setMessage(report) }
        override fun setLevel(level: Float) { overlay?.setLevel(level) }

        override fun askConfirm(question: String, onYes: () -> Unit, onNo: () -> Unit) {
            showConfirm(question, onYes, onNo)
        }
    }

    fun speak(text: String) = core.voiceEngine.speak(text)

    // ------------------------------------------------------------------ //
    // Подтверждение
    // ------------------------------------------------------------------ //

    private fun showConfirm(question: String, onYes: () -> Unit, onNo: () -> Unit) {
        main.post {
            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val d = resources.displayMetrics.density
            val pad = (18 * d).toInt()

            val root = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = GradientDrawable().apply {
                    cornerRadius = 22 * d
                    setColor(Color.parseColor("#F2141A2A"))
                    setStroke(1, Color.parseColor("#3D4C8C"))
                }
                setPadding(pad, pad, pad, pad)
            }
            val text = TextView(this).apply {
                text = question
                setTextColor(Color.parseColor("#EAF0FF"))
                textSize = 16f
            }
            val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            fun btn(label: String, action: () -> Unit) {
                val b = Button(this).apply {
                    text = label
                    setTextColor(Color.WHITE)
                    background = GradientDrawable().apply {
                        cornerRadius = 12 * d
                        setColor(Color.parseColor("#3D4C8C"))
                    }
                    setOnClickListener {
                        runCatching { wm.removeView(root) }
                        action()
                    }
                }
                buttons.addView(b, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = (6 * d).toInt(); marginEnd = (6 * d).toInt()
                })
            }
            btn("Да") { onYes() }
            btn("Нет") { onNo() }

            root.addView(text)
            root.addView(buttons)

            val p = WindowManager.LayoutParams(
                (320 * d).toInt(), WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            ).apply { gravity = Gravity.CENTER }
            runCatching { wm.addView(root, p) }
        }
    }

    // ------------------------------------------------------------------ //
    // Уведомление
    // ------------------------------------------------------------------ //

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel),
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = getString(R.string.notification_channel_desc) }
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, com.nova.assistant.MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, FloatingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_nova)
            .setContentTitle("NOVA")
            .setContentText("Помощник активен. Скажите «Нова» или нажмите на персонажа.")
            .setContentIntent(open)
            .addAction(R.drawable.ic_stop, getString(R.string.stop), stop)
            .setOngoing(true)
            .build()
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIF_ID, notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }
}
