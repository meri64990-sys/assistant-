package com.nova.assistant.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.nova.assistant.R

/** Приёмник таймера и напоминаний NOVA. */
class TimerReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val label = intent.getStringExtra("label") ?: "NOVA"
        val action = intent.action ?: return
        val text = when (action) {
            ACTION_TIMER -> "Время вышло: $label"
            ACTION_REMINDER -> "Напоминание: $label"
            else -> label
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return

        val notification = NotificationCompat.Builder(context, FloatingService.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_nova)
            .setContentTitle("NOVA")
            .setContentText(text)
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(action.hashCode(), notification) }

        // Если плавающий сервис активен — произносим уведомление.
        FloatingService.instance?.speak(text)
    }

    companion object {
        const val ACTION_TIMER = "com.nova.assistant.TIMER"
        const val ACTION_REMINDER = "com.nova.assistant.REMINDER"
    }
}
