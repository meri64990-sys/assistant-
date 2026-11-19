package com.nova.assistant.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.nova.assistant.settings.SettingsManager

/**
 * Управление плавающим окном: персонаж, панель ввода, состояния.
 * Окно типа TYPE_APPLICATION_OVERLAY — работает поверх других приложений.
 */
class OverlayController(
    private val context: Context,
    private val settings: SettingsManager,
    private val listener: Listener
) {

    interface Listener {
        fun onTap() {}
        fun onDoubleTap() {}
        fun onLongPress() {}
        fun onMic() {}
        fun onStop() {}
        fun onHide() {}
        fun onSendText(text: String) {}
        fun onResize(delta: Float) {}
    }

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val d = context.resources.displayMetrics.density

    private var root: FrameLayout? = null
    private var character: NovaCharacterView? = null
    private var panel: LinearLayout? = null
    private var statusText: TextView? = null
    private var input: EditText? = null
    private var params: WindowManager.LayoutParams? = null

    private var expanded = false
    private var shown = false

    val isShown: Boolean get() = shown

    // ------------------------------------------------------------------ //

    @SuppressLint("ClickableViewAccessibility")
    fun show() {
        if (shown) return
        if (!android.provider.Settings.canDrawOverlays(context)) return
        val ctx = context
        val char = NovaCharacterView(ctx).apply {
            scaleFactor = settings.characterScale
            state = CharacterState.IDLE
            onTap = { if (expanded) collapse() else expand() }
            onDoubleTap = { listener.onDoubleTap() }
            onLongPress = { listener.onLongPress() }
            onDrag = { dx, dy -> moveBy(dx, dy) }
        }
        val rootView = FrameLayout(ctx).apply { setBackgroundColor(Color.TRANSPARENT) }
        val size = (72 * d).toInt()
        val lp = FrameLayout.LayoutParams(size, size, Gravity.CENTER)
        rootView.addView(char, lp)

        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = (16 * d).toInt()
            y = (140 * d).toInt()
        }

        runCatching { wm.addView(rootView, p) }.onSuccess {
            root = rootView
            character = char
            params = p
            shown = true
        }
    }

    fun hide() {
        if (!shown) return
        runCatching { wm.removeView(root) }
        root = null
        character = null
        panel = null
        shown = false
        expanded = false
    }

    fun setState(state: CharacterState) {
        character?.state = state
    }

    fun setLevel(level: Float) {
        character?.level = level
    }

    fun setMessage(text: String) {
        statusText?.text = text
    }

    fun expand() {
        if (expanded || root == null) return
        buildPanel()
        root?.addView(panel)
        expanded = true
        // Панель должна получать фокус для ввода текста.
        val p = params ?: return
        p.flags = p.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        runCatching { wm.updateViewLayout(root, p) }
    }

    fun collapse() {
        if (!expanded) return
        panel?.let { root?.removeView(it) }
        expanded = false
        val p = params ?: return
        p.flags = p.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        runCatching { wm.updateViewLayout(root, p) }
    }

    fun isExpanded(): Boolean = expanded

    private fun moveBy(dx: Float, dy: Float) {
        val p = params ?: return
        p.x = (p.x - dx).coerceAtLeast(0f).toInt()
        p.y = (p.y + dy).coerceAtLeast(0f).toInt()
        runCatching { wm.updateViewLayout(root, p) }
    }

    private fun buildPanel() {
        if (panel != null) return
        val ctx = context
        val pad = (14 * d).toInt()
        val bg = GradientDrawable().apply {
            cornerRadius = 20 * d
            setColor(Color.parseColor("#E6141A2A"))
            setStroke(1, Color.parseColor("#3D4C8C"))
        }
        val panelView = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = bg
            setPadding(pad, pad, pad, pad)
            layoutParams = FrameLayout.LayoutParams(
                (300 * d).toInt(), FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL or Gravity.TOP
            )
        }
        val title = TextView(ctx).apply {
            text = "NOVA"
            setTextColor(Color.parseColor("#6C8CFF"))
            textSize = 12f
            letterSpacing = 0.15f
        }
        statusText = TextView(ctx).apply {
            text = "Слушаю…"
            setTextColor(Color.parseColor("#EAF0FF"))
            textSize = 15f
            setPadding(0, pad / 2, 0, pad / 2)
        }
        input = EditText(ctx).apply {
            hint = "Текст команды…"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#8A93AD"))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 1
            maxLines = 3
            setBackgroundColor(Color.parseColor("#222B44"))
            setPadding(pad / 2, pad / 2, pad / 2, pad / 2)
        }
        val buttons = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        fun btn(text: String, action: () -> Unit): Button {
            val b = Button(ctx).apply {
                this.text = text
                setTextColor(Color.WHITE)
                background = GradientDrawable().apply {
                    cornerRadius = 12 * d
                    setColor(Color.parseColor("#2A3554"))
                }
                setOnClickListener { action() }
            }
            buttons.addView(b, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = (4 * d).toInt(); marginEnd = (4 * d).toInt()
            })
            return b
        }
        btn("Микрофон") { listener.onMic() }
        btn("Стоп") { listener.onStop() }
        btn("Скрыть") { listener.onHide() }
        btn("+") { resize(1.1f) }
        btn("−") { resize(0.9f) }

        val send = Button(ctx).apply {
            text = "Отправить"
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                cornerRadius = 12 * d
                setColor(Color.parseColor("#3D4C8C"))
            }
            setOnClickListener {
                val t = input?.text?.toString()?.trim().orEmpty()
                if (t.isNotEmpty()) {
                    listener.onSendText(t)
                    input?.text?.clear()
                }
            }
        }

        panelView.addView(title)
        panelView.addView(statusText)
        panelView.addView(input)
        panelView.addView(buttons)
        panelView.addView(send)
        panel = panelView
    }

    private fun resize(factor: Float) {
        val c = character ?: return
        val newScale = (c.scaleFactor * factor).coerceIn(0.6f, 1.6f)
        c.scaleFactor = newScale
        settings.characterScale = newScale
        listener.onResize(newScale)
    }
}
