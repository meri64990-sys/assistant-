package com.nova.assistant.voice

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File

/**
 * Офлайн-распознавание речи через Vosk. Работает без интернета и без
 * API-ключей, но требует установленной модели:
 *
 *   Android/data/com.nova.assistant/files/vosk-model/
 *
 * Модель можно скачать с https://alphacephei.com/vosk/models (например,
 * vosk-model-small-ru-0.22) и распаковать в эту паперу.
 * Если модели нет — [start] возвращает false, и NOVA использует системный
 * SpeechRecognizer.
 */
class VoskStt(
    private val modelDir: String,
    private val onPartial: (String) -> Unit,
    private val onFinal: (String) -> Unit,
    private val onError: (String) -> Unit
) {

    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var thread: Thread? = null
    @Volatile private var running = false

    fun isModelAvailable(): Boolean = File(modelDir).isDirectory

    fun start(): Boolean {
        if (!isModelAvailable()) return false
        return try {
            model = Model(modelDir)
            recognizer = Recognizer(model, 16000f)
            running = true
            thread = Thread { loop() }.also { it.start() }
            true
        } catch (e: Exception) {
            onError("Vosk: " + (e.message ?: "не удалось запустить распознавание"))
            false
        }
    }

    private fun loop() {
        val sampleRate = 16000
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) { onError("Vosk: микрофон недоступен"); return }
        val record = AudioRecord(
            MediaRecorder.AudioSource.MIC, sampleRate,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf * 2
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            onError("Vosk: не удалось открыть микрофон")
            record.release()
            return
        }
        record.startRecording()
        val buf = ShortArray(4096)
        val rec = recognizer
        try {
            while (running && rec != null) {
                val n = record.read(buf, 0, buf.size)
                if (n <= 0) continue
                val bytes = toBytes(buf, n)
                if (rec.acceptWaveForm(bytes, bytes.size)) {
                    val text = extractText(rec.result)
                    if (text.isNotBlank()) onFinal(text)
                } else {
                    val text = extractText(rec.partialResult)
                    if (text.isNotBlank()) onPartial(text)
                }
            }
        } catch (e: Exception) {
            if (running) onError("Vosk: " + (e.message ?: "ошибка"))
        } finally {
            runCatching { record.stop() }
            record.release()
        }
    }

    fun stop() {
        running = false
        thread?.let { runCatching { it.join(1200) } }
        thread = null
    }

    private fun extractText(json: String): String = try {
        val obj = JSONObject(json)
        when {
            obj.has("partial") -> obj.optString("partial")
            obj.has("text") -> obj.optString("text")
            else -> ""
        }
    } catch (e: Exception) {
        ""
    }

    private fun toBytes(shorts: ShortArray, n: Int): ByteArray {
        val out = ByteArray(n * 2)
        for (i in 0 until n) {
            val v = shorts[i]
            out[i * 2] = (v.toInt() and 0xFF).toByte()
            out[i * 2 + 1] = ((v.toInt() shr 8) and 0xFF).toByte()
        }
        return out
    }
}
