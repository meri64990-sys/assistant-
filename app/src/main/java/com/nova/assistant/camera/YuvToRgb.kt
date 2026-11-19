package com.nova.assistant.camera

import android.graphics.Bitmap
import android.graphics.ImageFormat
import androidx.camera.core.ImageProxy
import java.nio.ByteBuffer

/**
 * Конвертация кадра CameraX (YUV_420_888) в Bitmap RGB_565,
 * пригодный для android.media.FaceDetector. Кадр уменьшается —
 * это заметно снижает нагрузку на процессор.
 */
object YuvToRgb {

    fun convert(image: ImageProxy, targetWidth: Int = 160): Bitmap? {
        if (image.format != ImageFormat.YUV_420_888) return null
        return try {
            val yPlane = image.planes[0]
            val uPlane = image.planes[1]
            val vPlane = image.planes[2]

            val srcW = image.width
            val srcH = image.height
            val w = targetWidth
            val h = (srcH * targetWidth / srcW)

            val yBuf = yPlane.buffer
            val uBuf = uPlane.buffer
            val vBuf = vPlane.buffer

            val yRow = yPlane.rowStride
            val yPix = yPlane.pixelStride
            val uRow = uPlane.rowStride
            val uPix = uPlane.pixelStride
            val vRow = vPlane.rowStride
            val vPix = vPlane.pixelStride

            val out = ShortArray(w * h)
            for (j in 0 until h) {
                val sy = (j * srcH / h).coerceIn(0, srcH - 1)
                for (i in 0 until w) {
                    val sx = (i * srcW / w).coerceIn(0, srcW - 1)

                    val yVal = (yBuf.get(sy * yRow + sx * yPix).toInt() and 0xFF) - 16
                    val su = (sx / 2).coerceIn(0, srcW / 2 - 1)
                    val sj = (sy / 2).coerceIn(0, srcH / 2 - 1)
                    val uVal = (uBuf.get(sj * uRow + su * uPix).toInt() and 0xFF) - 128
                    val vVal = (vBuf.get(sj * vRow + su * vPix).toInt() and 0xFF) - 128

                    var r = (298 * yVal + 409 * vVal + 128) shr 8
                    var g = (298 * yVal - 100 * uVal - 208 * vVal + 128) shr 8
                    var b = (298 * yVal + 516 * uVal + 128) shr 8
                    r = r.coerceIn(0, 255)
                    g = g.coerceIn(0, 255)
                    b = b.coerceIn(0, 255)

                    out[j * w + i] = (((r shr 3) shl 11) or ((g shr 2) shl 5) or (b shr 3)).toShort()
                }
            }
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565)
            val bytes = ByteBuffer.allocate(out.size * 2)
            for (s in out) {
                bytes.put((s.toInt() and 0xFF).toByte())
                bytes.put(((s.toInt() shr 8) and 0xFF).toByte())
            }
            bytes.rewind()
            bmp.copyPixelsFromBuffer(bytes)
            bmp
        } catch (e: Exception) {
            null
        }
    }
}
