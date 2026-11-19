package com.nova.assistant.camera

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.PointF
import android.media.FaceDetector
import android.os.Bundle
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.nova.assistant.R
import com.nova.assistant.overlay.CharacterState
import com.nova.assistant.overlay.NovaCharacterView
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Визуальный режим: настоящий видеопоток с фронтальной камеры + персонаж,
 * который реагирует на лицо в кадре. Работает только с разрешением камеры.
 */
class CameraAssistantActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var character: NovaCharacterView
    private lateinit var status: TextView
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()

    private var faceCount = 0
    private var faceMid = PointF(0.5f, 0.5f)
    private var cameraReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera)

        previewView = findViewById(R.id.preview)
        status = findViewById(R.id.status)
        character = NovaCharacterView(this).apply {
            state = CharacterState.IDLE
            scaleFactor = 1.2f
        }
        val container = findViewById<FrameLayout>(R.id.characterContainer)
        container.addView(character, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT
        ))

        findViewById<Button>(R.id.btnOff).setOnClickListener { finish() }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 200)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 200) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startCamera()
            else {
                // Честная альтернатива: персонаж без видеопотока.
                status.text = "Камера не разрешена. Показан автономный режим без видеопотока."
                character.state = CharacterState.IDLE
            }
        }
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(executor) { image ->
                    processFrame(image)
                    image.close()
                }
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, preview, analysis)
                cameraReady = true
                status.text = "Камера включена. Персонаж реагирует на лицо в кадре."
            } catch (e: Exception) {
                status.text = "Камера недоступна: " + (e.message ?: "не удалось открыть").let { it }
                character.state = CharacterState.ERROR
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun processFrame(image: ImageProxy) {
        val bmp: Bitmap = YuvToRgb.convert(image) ?: return
        val faces = arrayOfNulls<FaceDetector.Face>(1)
        val detector = FaceDetector(bmp.width, bmp.height, 1)
        val found = try { detector.findFaces(bmp, faces) } catch (e: Exception) { 0 }
        val face = faces[0]
        if (found > 0 && face != null) {
            val mid = PointF()
            face.getMidPoint(mid)
            faceMid = PointF(mid.x / bmp.width, mid.y / bmp.height)
            faceCount = found
        } else {
            faceCount = 0
        }
        runOnUiThread { updateCharacter() }
    }

    private fun updateCharacter() {
        if (faceCount > 0) {
            character.state = CharacterState.LISTENING
            // Персонаж смещается к лицу (в пределах контейнера).
            val container = character.parent as? FrameLayout ?: return
            val w = container.width - character.width
            val h = container.height - character.height
            character.translationX = (faceMid.x - 0.5f) * w * 0.6f
            character.translationY = (faceMid.y - 0.5f) * h * 0.6f
            status.text = "Вижу лицо. Персонаж следит за вами."
        } else {
            character.state = CharacterState.IDLE
            character.translationX = 0f
            character.translationY = 0f
            if (cameraReady) status.text = "Камера включена. Лицо не найдено — жду."
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdown()
    }
}
