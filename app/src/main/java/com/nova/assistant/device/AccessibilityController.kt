package com.nova.assistant.device

import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.util.Base64
import android.accessibilityservice.AccessibilityService
import com.nova.assistant.service.NovaAccessibilityService
import android.accessibilityservice.GestureDescription
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Фасад над службой спец. возможностей. Все операции с деревом интерфейса
 * выполняются в главном потоке (требование Android).
 */
object AccessibilityController {

    data class ScreenElement(
        val index: Int,
        val text: String,
        val desc: String,
        val className: String,
        val rect: Rect,
        val clickable: Boolean,
        val editable: Boolean,
        val scrollable: Boolean
    )

    data class ScreenSnapshot(
        val elements: List<ScreenElement>,
        val text: String,
        val packageName: String?,
        val activityName: String?
    ) {
        val buttons: List<ScreenElement> get() = elements.filter { it.clickable || it.text.isNotBlank() }
    }

    private const val MAX_ELEMENTS = 120

    private var service: NovaAccessibilityService? = null
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var lastPackageName: String? = null
    @Volatile private var lastSnapshot: ScreenSnapshot? = null

    fun bind(s: NovaAccessibilityService) { service = s }
    fun unbind() { service = null }

    fun isReady(): Boolean = service != null

    fun onEvent(e: AccessibilityEvent?) {
        if (e == null) return
        if (e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            e.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) {
            e.packageName?.let { lastPackageName = it.toString() }
        }
    }

    fun currentPackage(): String? = lastPackageName

    // ------------------------------------------------------------------ //
    // Снимок экрана
    // ------------------------------------------------------------------ //

    fun snapshot(cb: (ScreenSnapshot?) -> Unit) {
        main.post {
            val s = service
            if (s == null) { cb(null); return@post }
            val snap = buildSnapshot(s)
            lastSnapshot = snap
            cb(snap)
        }
    }

    fun lastSnapshot(): ScreenSnapshot? = lastSnapshot

    private fun buildSnapshot(s: NovaAccessibilityService): ScreenSnapshot {
        val out = mutableListOf<ScreenElement>()
        val sb = StringBuilder()
        val root = s.rootInActiveWindow
        if (root != null) {
            walk(root, out, sb)
            if (out.isEmpty()) {
                // Дерево может быть недоступно — берём текст из события.
                sb.append(root.text ?: "")
            }
        }
        val pkg = root?.packageName?.toString() ?: lastPackageName
        return ScreenSnapshot(out, sb.toString().trim(), pkg, null)
    }

    private fun walk(
        node: AccessibilityNodeInfo,
        out: MutableList<ScreenElement>,
        sb: StringBuilder
    ) {
        if (out.size >= MAX_ELEMENTS) return
        if (!node.isVisibleToUser) return
        val text = (node.text ?: node.contentDescription)?.toString()?.trim().orEmpty()
        val clickable = node.isClickable
        val editable = node.isEditable
        val scrollable = node.isScrollable
        if (text.isNotEmpty() || clickable || editable || scrollable) {
            val r = Rect()
            node.getBoundsInScreen(r)
            if (r.width() > 0 && r.height() > 0) {
                if (text.isNotEmpty()) {
                    if (sb.isNotEmpty()) sb.append(". ")
                    sb.append(text)
                }
                out += ScreenElement(
                    index = out.size,
                    text = text,
                    desc = node.contentDescription?.toString().orEmpty(),
                    className = node.className?.toString().orEmpty(),
                    rect = r,
                    clickable = clickable,
                    editable = editable,
                    scrollable = scrollable
                )
            }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            walk(child, out, sb)
        }
    }

    private fun findNodeByIndex(root: AccessibilityNodeInfo?, index: Int): AccessibilityNodeInfo? {
        if (root == null) return null
        val counter = intArrayOf(0)
        return walkFind(root, index, counter)
    }

    private fun walkFind(node: AccessibilityNodeInfo, index: Int, counter: IntArray): AccessibilityNodeInfo? {
        if (!node.isVisibleToUser) return null
        val text = (node.text ?: node.contentDescription)?.toString()?.trim().orEmpty()
        if (text.isNotEmpty() || node.isClickable || node.isEditable || node.isScrollable) {
            val r = Rect(); node.getBoundsInScreen(r)
            if (r.width() > 0 && r.height() > 0) {
                if (counter[0] == index) return node
                counter[0]++
            }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = walkFind(child, index, counter)
            if (found != null) return found
        }
        return null
    }

    // ------------------------------------------------------------------ //
    // Действия
    // ------------------------------------------------------------------ //

    fun tapElement(index: Int, cb: (Boolean) -> Unit) {
        main.post {
            val s = service ?: return@post cb(false)
            val node = findNodeByIndex(s.rootInActiveWindow, index)
            if (node == null) { cb(false); return@post }
            val r = Rect(); node.getBoundsInScreen(r)
            cb(tap(r.centerX(), r.centerY()))
        }
    }

    fun tap(x: Int, y: Int, cb: (Boolean) -> Unit) {
        main.post { cb(tapInternal(x, y)) }
    }

    private fun tapInternal(x: Int, y: Int): Boolean {
        val s = service ?: return false
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        return gesture(s, path, 80)
    }

    fun typeElement(index: Int, text: String, cb: (Boolean) -> Unit) {
        main.post {
            val s = service ?: return@post cb(false)
            val node = findNodeByIndex(s.rootInActiveWindow, index) ?: return@post cb(false)
            cb(typeInternal(node, text))
        }
    }

    fun typeIntoEditable(text: String, cb: (Boolean) -> Unit) {
        main.post {
            val s = service ?: return@post cb(false)
            val root = s.rootInActiveWindow ?: return@post cb(false)
            val node = firstEditable(root)
            if (node == null) { cb(false); return@post }
            cb(typeInternal(node, text))
        }
    }

    private fun firstEditable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable && node.isVisibleToUser) return node
        for (i in 0 until node.childCount) {
            val c = node.getChild(i) ?: continue
            val f = firstEditable(c)
            if (f != null) return f
        }
        return null
    }

    private fun typeInternal(node: AccessibilityNodeInfo, text: String): Boolean {
        return try {
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        } catch (e: Exception) {
            false
        }
    }

    fun swipe(direction: String, cb: (Boolean) -> Unit) {
        main.post {
            val s = service ?: return@post cb(false)
            val dm = s.resources.displayMetrics
            val cx = dm.widthPixels / 2f
            val cy = dm.heightPixels / 2f
            val dx = dm.widthPixels * 0.35f
            val dy = dm.heightPixels * 0.35f
            val (x2, y2) = when (direction.lowercase()) {
                "up" -> cx to (cy - dy)
                "down" -> cx to (cy + dy)
                "left" -> (cx - dx) to cy
                else -> (cx + dx) to cy
            }
            val path = Path().apply { moveTo(cx, cy); lineTo(x2, y2) }
            cb(gesture(s, path, 250))
        }
    }

    fun back(cb: (Boolean) -> Unit) {
        main.post { cb(service?.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK) ?: false) }
    }

    fun home(cb: (Boolean) -> Unit) {
        main.post { cb(service?.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME) ?: false) }
    }

    fun recents(cb: (Boolean) -> Unit) {
        main.post { cb(service?.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS) ?: false) }
    }

    private fun gesture(s: NovaAccessibilityService, path: Path, duration: Long): Boolean {
        val latch = CountDownLatch(1)
        var ok = false
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
            .build()
        val dispatched = s.dispatchGesture(g, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) { ok = true; latch.countDown() }
            override fun onCancelled(gestureDescription: GestureDescription?) { ok = false; latch.countDown() }
        }, null)
        if (dispatched) latch.await(2, TimeUnit.SECONDS)
        return dispatched && ok
    }

    // ------------------------------------------------------------------ //
    // Скриншот (API 30+)
    // ------------------------------------------------------------------ //

    fun screenshot(cb: (Bitmap?) -> Unit) {
        main.post {
            val s = service
            if (s == null || Build.VERSION.SDK_INT < 30) { cb(null); return@post }
            runCatching {
                s.takeScreenshot(Display.DEFAULT_DISPLAY, main, object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                        try {
                            val bmp = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                                ?.copy(Bitmap.Config.ARGB_8888, false)
                            result.hardwareBuffer.close()
                            cb(bmp)
                        } catch (e: Exception) {
                            cb(null)
                        }
                    }
                    override fun onFailure(errorCode: Int) { cb(null) }
                })
            }.onFailure { cb(null) }
        }
    }

    fun screenshotBase64(cb: (String?) -> Unit) {
        screenshot { bmp ->
            if (bmp == null) { cb(null); return@screenshot }
            val bos = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 70, bos)
            cb(Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP))
        }
    }
}
