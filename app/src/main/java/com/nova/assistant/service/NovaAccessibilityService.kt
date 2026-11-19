package com.nova.assistant.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import com.nova.assistant.device.AccessibilityController

/**
 * «Руки и глаза» NOVA: читает дерево интерфейса, нажимает, вводит текст,
 * делает скриншоты. Работает только после явного включения пользователем
 * в настройках спец. возможностей.
 */
class NovaAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: NovaAccessibilityService? = null
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        AccessibilityController.bind(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        AccessibilityController.onEvent(event)
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        AccessibilityController.unbind()
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        AccessibilityController.unbind()
        instance = null
        super.onDestroy()
    }
}
