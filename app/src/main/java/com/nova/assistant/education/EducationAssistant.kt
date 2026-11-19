package com.nova.assistant.education

import android.content.Context
import com.nova.assistant.NovaApp
import com.nova.assistant.core.ConversationContext
import com.nova.assistant.core.Intent
import com.nova.assistant.core.TaskPlan
import com.nova.assistant.core.TaskPlanner
import com.nova.assistant.device.AccessibilityController
import com.nova.assistant.device.ScreenAnalyzer
import com.nova.assistant.search.SearchEngine

/**
 * Помощь с учёбой: находит задание/тест в открытом образовательном
 * приложении, читает вопросы и ищет ответы в открытых источниках.
 *
 * Честно не отправляет ответы за пользователя: только объясняет и помогает.
 */
class EducationAssistant(
    private val context: Context,
    private val search: SearchEngine
) {

    fun plan(goal: String, intents: List<Intent>): TaskPlan {
        val ctx = NovaApp.conversation ?: ConversationContext()
        return TaskPlanner.plan(goal, intents, ctx)
    }

    /** Описывает, что сейчас видно на экране (задание, вопросы). */
    fun describeScreen(onDone: (String) -> Unit) {
        ScreenAnalyzer.read(context) { snap ->
            onDone(
                if (snap == null) "Не могу прочитать экран: включите службу доступа."
                else ScreenAnalyzer.describe(context, snap)
            )
        }
    }

    /** Ищет ответ на вопрос в интернете (без платного API). */
    fun findAnswer(question: String, onDone: (String?) -> Unit) {
        search.search(question) { onDone(it) }
    }

    /** Читает текст вопросов с экрана. */
    fun readQuestions(onDone: (String) -> Unit) {
        AccessibilityController.snapshot { snap ->
            onDone(snap?.text?.take(2000) ?: "")
        }
    }
}
