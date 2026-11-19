package com.nova.assistant.game

import android.content.Context
import com.nova.assistant.device.AccessibilityController
import com.nova.assistant.device.ScreenAnalyzer

/**
 * Игровой помощник: анализирует видимый экран, описывает состояние и
 * предлагает следующее действие. Не выдумывает игровую механику —
 * опирается только на то, что реально видно на экране.
 */
class GameAssistant(private val context: Context) {

    /** Описывает текущее состояние игры. */
    fun analyze(onDone: (String) -> Unit) {
        ScreenAnalyzer.read(context) { snap ->
            if (snap == null) {
                onDone("Не вижу экран. Включите службу доступа, и я смогу анализировать игру.")
                return@read
            }
            val isGame = ScreenAnalyzer.looksLikeGame(context, snap)
            val base = ScreenAnalyzer.describe(context, snap)
            onDone(if (isGame) "Похоже, это игра. $base" else base)
        }
    }

    /** Предлагает следующий шаг на основе видимых элементов. */
    fun suggest(onDone: (String) -> Unit) {
        AccessibilityController.snapshot { snap ->
            if (snap == null) { onDone("Экран недоступен."); return@snapshot }
            val buttons = snap.buttons.map { it.text }.filter { it.isNotBlank() }.distinct()
            val suggestion = when {
                buttons.isEmpty() -> "На экране нет кнопок. Скажите, что нужно сделать, и я попробую."
                else -> "Могу нажать: " + buttons.take(5).joinToString(", ") +
                    ". Скажите, например: «нажми " + buttons.first() + "»."
            }
            onDone(suggestion)
        }
    }

    /** Выполняет нажатие по названию кнопки. */
    fun tapButton(query: String, onDone: (Boolean) -> Unit) {
        AccessibilityController.snapshot { snap ->
            val el = ScreenAnalyzer.find(snap, query)
            if (el == null) { onDone(false); return@snapshot }
            AccessibilityController.tap(el.rect.centerX(), el.rect.centerY()) { onDone(it) }
        }
    }
}
