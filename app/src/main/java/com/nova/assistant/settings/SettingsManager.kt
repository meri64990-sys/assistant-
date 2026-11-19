package com.nova.assistant.settings

import android.content.Context
import android.content.SharedPreferences

/**
 * Все настройки NOVA. Хранятся локально в SharedPreferences.
 */
class SettingsManager(context: Context) {

    private val sp: SharedPreferences = context.getSharedPreferences("nova_settings", Context.MODE_PRIVATE)

    /** Режим работы: eco / standard / intensive. */
    var performanceMode: String
        get() = sp.getString(KEY_MODE, "standard") ?: "standard"
        set(v) = sp.edit().putString(KEY_MODE, v).apply()

    /** Голосовые ответы включены. */
    var voiceEnabled: Boolean
        get() = sp.getBoolean(KEY_VOICE, true)
        set(v) = sp.edit().putBoolean(KEY_VOICE, v).apply()

    /** Постоянное ожидание команды («всегда слушаю»). */
    var alwaysListening: Boolean
        get() = sp.getBoolean(KEY_ALWAYS, true)
        set(v) = sp.edit().putBoolean(KEY_ALWAYS, v).apply()

    /** Реакция на слово-будильник («Нова», «эй помощник»). */
    var wakeWordEnabled: Boolean
        get() = sp.getBoolean(KEY_WAKE, true)
        set(v) = sp.edit().putBoolean(KEY_WAKE, v).apply()

    /** Чувствительность микрофона 0..100. */
    var micSensitivity: Int
        get() = sp.getInt(KEY_SENS, 60)
        set(v) = sp.edit().putInt(KEY_SENS, v.coerceIn(0, 100)).apply()

    /** Размер персонажа 0.6..1.6. */
    var characterScale: Float
        get() = sp.getFloat(KEY_SCALE, 1.0f)
        set(v) = sp.edit().putFloat(KEY_SCALE, v.coerceIn(0.6f, 1.6f)).apply()

    /** Персонаж виден. */
    var characterVisible: Boolean
        get() = sp.getBoolean(KEY_VISIBLE, true)
        set(v) = sp.edit().putBoolean(KEY_VISIBLE, v).apply()

    /** Плавающий режим включён (оверлей поверх приложений). */
    var overlayEnabled: Boolean
        get() = sp.getBoolean(KEY_OVERLAY, true)
        set(v) = sp.edit().putBoolean(KEY_OVERLAY, v).apply()

    /** Визуальный режим камеры. */
    var cameraMode: Boolean
        get() = sp.getBoolean(KEY_CAMERA, false)
        set(v) = sp.edit().putBoolean(KEY_CAMERA, v).apply()

    /** Подтверждать чувствительные действия. */
    var confirmSensitive: Boolean
        get() = sp.getBoolean(KEY_CONFIRM, true)
        set(v) = sp.edit().putBoolean(KEY_CONFIRM, v).apply()

    /** Офлайн-распознавание речи (Vosk), если модель установлена. */
    var offlineStt: Boolean
        get() = sp.getBoolean(KEY_OFFLINE_STT, true)
        set(v) = sp.edit().putBoolean(KEY_OFFLINE_STT, v).apply()

    /** Громкость 0..1. */
    var volume: Float
        get() = sp.getFloat(KEY_VOLUME, 1.0f)
        set(v) = sp.edit().putFloat(KEY_VOLUME, v.coerceIn(0f, 1f)).apply()

    /** Скорость речи 0.5..1.5. */
    var speechRate: Float
        get() = sp.getFloat(KEY_RATE, 1.0f)
        set(v) = sp.edit().putFloat(KEY_RATE, v.coerceIn(0.5f, 1.5f)).apply()

    /** Приватность: не сохранять историю задач. */
    var saveHistory: Boolean
        get() = sp.getBoolean(KEY_HISTORY, true)
        set(v) = sp.edit().putBoolean(KEY_HISTORY, v).apply()

    /** Приватность: не сохранять пароли/финансовые данные в памяти. */
    var privacyFilter: Boolean
        get() = sp.getBoolean(KEY_PRIVACY, true)
        set(v) = sp.edit().putBoolean(KEY_PRIVACY, v).apply()

    /** Отладочный журнал действий. */
    var debugLog: Boolean
        get() = sp.getBoolean(KEY_DEBUG, false)
        set(v) = sp.edit().putBoolean(KEY_DEBUG, v).apply()

    /** Экономичный режим: отключает постоянное слушание и анимации. */
    val isEco: Boolean get() = performanceMode == "eco"
    val isIntensive: Boolean get() = performanceMode == "intensive"

    fun clearAll() {
        sp.edit().clear().apply()
    }

    companion object {
        private const val KEY_MODE = "mode"
        private const val KEY_VOICE = "voice"
        private const val KEY_ALWAYS = "always"
        private const val KEY_WAKE = "wake"
        private const val KEY_SENS = "sens"
        private const val KEY_SCALE = "scale"
        private const val KEY_VISIBLE = "visible"
        private const val KEY_OVERLAY = "overlay"
        private const val KEY_CAMERA = "camera"
        private const val KEY_CONFIRM = "confirm"
        private const val KEY_OFFLINE_STT = "offline_stt"
        private const val KEY_VOLUME = "volume"
        private const val KEY_RATE = "rate"
        private const val KEY_HISTORY = "history"
        private const val KEY_PRIVACY = "privacy"
        private const val KEY_DEBUG = "debug"
    }
}
