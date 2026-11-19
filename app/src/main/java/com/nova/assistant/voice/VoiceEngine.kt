package com.nova.assistant.voice

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.media.AudioManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.nova.assistant.core.FuzzyMatcher
import com.nova.assistant.settings.SettingsManager
import java.util.Locale

/**
 * Голосовой контур NOVA: распознавание речи, синтез речи, прерывание
 * (barge-in), индикация уровня громкости, слово-будильник.
 *
 * Распознавание — системное (SpeechRecognizer) или офлайн (Vosk, если
 * установлена модель). Синтез — системный TextToSpeech, русский по умолчанию.
 */
class VoiceEngine(
    private val context: Context,
    private val settings: SettingsManager,
    private val callbacks: Callbacks
) {

    interface Callbacks {
        fun onPartial(text: String) {}
        fun onResult(text: String) {}
        fun onWakeWord() {}
        fun onUserSpeaking() {}
        fun onError(message: String) {}
        fun onRms(level: Float) {}
        fun onListeningChanged(listening: Boolean) {}
    }

    private val main = Handler(Looper.getMainLooper())
    private var sr: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    @Volatile private var wantListening = false
    @Volatile private var wakeWordArmed = true
    @Volatile private var offline: VoskStt? = null
    private var useOffline = false

    val isListening: Boolean get() = wantListening

    init {
        main.post {
            tts = TextToSpeech(context) { status ->
                ttsReady = status == TextToSpeech.SUCCESS
                if (ttsReady) {
                    runCatching { tts?.language = Locale("ru") }
                    tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) { callbacks.onListeningChanged(false) }
                        override fun onDone(utteranceId: String?) { callbacks.onListeningChanged(wantListening) }
                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) { callbacks.onListeningChanged(wantListening) }
                    })
                }
            }
        }
    }

    // ------------------------------------------------------------------ //
    // Распознавание
    // ------------------------------------------------------------------ //

    fun isRecognitionAvailable(): Boolean =
        SpeechRecognizer.isRecognitionAvailable(context) || hasOfflineModel()

    fun hasOfflineModel(): Boolean {
        val dir = java.io.File(context.filesDir, "vosk-model")
        return dir.isDirectory
    }

    /** Одноразовое слушание команды. */
    fun listenOnce() {
        wantListening = true
        wakeWordArmed = false
        startRecognition()
    }

    /** Постоянное ожидание: слово-будильник + команда. */
    fun listenContinuously() {
        wantListening = true
        wakeWordArmed = settings.wakeWordEnabled
        if (settings.offlineStt && hasOfflineModel()) {
            startOffline()
        } else {
            startRecognition()
        }
    }

    fun stop() {
        wantListening = false
        main.post {
            runCatching { sr?.stopListening() }
            offline?.stop()
            offline = null
            callbacks.onListeningChanged(false)
        }
    }

    fun cancel() {
        wantListening = false
        main.post {
            runCatching { sr?.cancel() }
            offline?.stop()
            offline = null
        }
    }

    private fun startOffline() {
        offline?.stop()
        offline = VoskStt(
            modelDir = java.io.File(context.filesDir, "vosk-model").absolutePath,
            onPartial = { text -> main.post { handlePartial(text) } },
            onFinal = { text -> main.post { handleResult(text) } },
            onError = { msg -> main.post { callbacks.onError(msg) } }
        ).also {
            if (!it.start()) {
                offline = null
                startRecognition()
            }
        }
    }

    private fun startRecognition() {
        main.post {
            if (!wantListening) return@post
            if (sr == null) {
                sr = try {
                    SpeechRecognizer.createSpeechRecognizer(context)
                } catch (e: Exception) {
                    null
                }?.also { it.setRecognitionListener(listener) }
            }
            if (sr == null) {
                callbacks.onError("Распознавание речи недоступно на этом устройстве")
                return@post
            }
            val intent = android.content.Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            }
            runCatching { sr?.startListening(intent) }
        }
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) { callbacks.onListeningChanged(true) }
        override fun onBeginningOfSpeech() { callbacks.onUserSpeaking() }
        override fun onRmsChanged(rmsdB: Float) { callbacks.onRms(rmsdB) }
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}

        override fun onError(error: Int) {
            callbacks.onListeningChanged(false)
            if (!wantListening) return
            // Типичные ошибки: 7 (no match), 9 (insufficient permissions), 3 (audio).
            if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                restart()
            } else if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                callbacks.onError("Нет разрешения на микрофон")
                wantListening = false
            } else {
                restart()
            }
        }

        override fun onResults(results: Bundle?) {
            callbacks.onListeningChanged(false)
            if (!wantListening) return
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull { !it.isNullOrBlank() }
            if (text != null) {
                handleResult(text)
            } else {
                restart()
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull { !it.isNullOrBlank() } ?: return
            handlePartial(text)
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun handlePartial(text: String) {
        callbacks.onPartial(text)
        if (wakeWordArmed && isWakeWord(text)) {
            wakeWordArmed = false
            callbacks.onWakeWord()
        }
    }

    private fun handleResult(text: String) {
        if (wakeWordArmed && isWakeWord(text)) {
            wakeWordArmed = false
            callbacks.onWakeWord()
            // Продолжаем слушать: команда пойдёт следующим результатом.
            return
        }
        wakeWordArmed = settings.wakeWordEnabled && wantListening
        callbacks.onResult(text)
    }

    private fun isWakeWord(text: String): Boolean {
        val n = FuzzyMatcher.normalize(text)
        if (n.length > 40) return false
        return com.nova.assistant.core.AliasDictionary.wakeWords.any { w ->
            val wn = FuzzyMatcher.normalize(w)
            FuzzyMatcher.similarity(n, wn) >= 0.85f || n.startsWith(wn)
        }
    }

    private fun restart() {
        if (!wantListening) return
        main.postDelayed({
            if (wantListening) {
                runCatching { sr?.stopListening() }
                startRecognition()
            }
        }, 250)
    }

    // ------------------------------------------------------------------ //
    // Синтез речи
    // ------------------------------------------------------------------ //

    fun speak(text: String, flush: Boolean = true) {
        if (!settings.voiceEnabled || text.isBlank()) return
        main.post {
            if (!ttsReady) { callbacks.onError("Синтез речи ещё не готов"); return@post }
            runCatching {
                tts?.language = Locale("ru")
                tts?.setSpeechRate(settings.speechRate)
                val mode = if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
                tts?.speak(text, mode, null, "nova-${System.currentTimeMillis()}")
            }
        }
    }

    fun stopSpeaking() {
        main.post { runCatching { tts?.stop() } }
    }

    fun isSpeaking(): Boolean = tts?.isSpeaking == true

    /** Громкость голоса 0..1 (через _stream MUSIC). */
    fun setVolume(level: Float) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val v = (max * level.coerceIn(0f, 1f)).toInt().coerceIn(0, max)
        runCatching { am.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0) }
    }

    fun destroy() {
        wantListening = false
        main.post {
            runCatching { sr?.destroy() }
            sr = null
            offline?.stop()
            offline = null
            runCatching { tts?.stop() }
            runCatching { tts?.shutdown() }
            tts = null
        }
    }
}
