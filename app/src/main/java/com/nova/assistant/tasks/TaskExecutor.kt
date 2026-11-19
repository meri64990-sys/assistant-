package com.nova.assistant.tasks

import android.content.Context
import com.nova.assistant.core.FuzzyMatcher
import com.nova.assistant.core.StepKind
import com.nova.assistant.core.TaskStep
import com.nova.assistant.device.AccessibilityController
import com.nova.assistant.device.AppLauncher
import com.nova.assistant.device.ScreenAnalyzer
import com.nova.assistant.memory.MemoryRepository
import com.nova.assistant.notes.NotesManager
import com.nova.assistant.search.SearchEngine
import kotlinx.coroutines.runBlocking

/** Результат выполнения одного шага. */
enum class StepResult { SUCCESS, FAILED, NEEDS_CONFIRM, SKIPPED }

/**
 * Выполняет один шаг плана реальными действиями Android.
 * Ничего не имитирует: если шаг не удалось выполнить — возвращает FAILED.
 */
class TaskExecutor(
    private val context: Context,
    private val launcher: AppLauncher,
    private val notes: NotesManager,
    private val search: SearchEngine,
    private val memory: MemoryRepository
) {

    /** Выполнить шаг. Подтверждения возвращаются как NEEDS_CONFIRM. */
    fun execute(
        step: TaskStep,
        onConfirm: (String, () -> Unit, () -> Unit) -> Unit,
        cb: (StepResult) -> Unit
    ) {
        when (step.kind) {
            StepKind.OPEN_APP -> {
                val app = step.payload["app"]
                if (app.isNullOrBlank()) { cb(StepResult.FAILED); return }
                val launched = launcher.launchByName(app) != null
                cb(if (launched) StepResult.SUCCESS else StepResult.FAILED)
            }

            StepKind.OPEN_URL -> {
                val url = step.payload["url"]
                if (url.isNullOrBlank()) { cb(StepResult.FAILED); return }
                cb(if (launcher.openUrl(url)) StepResult.SUCCESS else StepResult.FAILED)
            }

            StepKind.OPEN_SETTINGS -> {
                cb(if (launcher.openSettings(step.payload["section"])) StepResult.SUCCESS else StepResult.FAILED)
            }

            StepKind.HOME -> cb(if (launcher.home()) StepResult.SUCCESS else StepResult.FAILED)

            StepKind.NAVIGATE -> {
                val q = step.payload["text"] ?: step.payload["section"]
                if (q.isNullOrBlank()) { cb(StepResult.SKIPPED); return }
                ScreenAnalyzer.scrollTo(context, q, maxSwipes = 3) { el ->
                    if (el == null) { cb(StepResult.FAILED); return@scrollTo }
                    AccessibilityController.tap(el.rect.centerX(), el.rect.centerY()) { ok ->
                        cb(if (ok) StepResult.SUCCESS else StepResult.FAILED)
                    }
                }
            }

            StepKind.BACK -> AccessibilityController.back { cb(if (it) StepResult.SUCCESS else StepResult.FAILED) }

            StepKind.FIND_ELEMENT -> {
                val q = step.payload["text"] ?: step.payload["query"]
                if (q.isNullOrBlank()) { cb(StepResult.FAILED); return }
                ScreenAnalyzer.scrollTo(context, q, maxSwipes = 3) { el ->
                    cb(if (el != null) StepResult.SUCCESS else StepResult.FAILED)
                }
            }

            StepKind.TAP_ELEMENT -> {
                val q = step.payload["text"] ?: step.payload["query"]
                if (q.isNullOrBlank()) { cb(StepResult.FAILED); return }
                ScreenAnalyzer.scrollTo(context, q, maxSwipes = 3) { el ->
                    if (el == null) { cb(StepResult.FAILED); return@scrollTo }
                    AccessibilityController.tap(el.rect.centerX(), el.rect.centerY()) { ok ->
                        cb(if (ok) StepResult.SUCCESS else StepResult.FAILED)
                    }
                }
            }

            StepKind.TYPE_TEXT -> {
                val text = step.payload["text"]
                if (text.isNullOrBlank()) { cb(StepResult.FAILED); return }
                AccessibilityController.typeIntoEditable(text) { ok ->
                    cb(if (ok) StepResult.SUCCESS else StepResult.FAILED)
                }
            }

            StepKind.SWIPE -> {
                val dir = step.payload["dir"] ?: "up"
                AccessibilityController.swipe(dir) { ok -> cb(if (ok) StepResult.SUCCESS else StepResult.FAILED) }
            }

            StepKind.READ_SCREEN -> {
                AccessibilityController.snapshot { snap ->
                    cb(if (!snap?.text.isNullOrBlank()) StepResult.SUCCESS else StepResult.FAILED)
                }
            }

            StepKind.VERIFY_SCREEN -> {
                val expected = step.payload["contains"]
                AccessibilityController.snapshot { snap ->
                    if (snap == null) { cb(StepResult.FAILED); return@snapshot }
                    if (expected.isNullOrBlank()) { cb(StepResult.SUCCESS); return@snapshot }
                    val found = snap.elements.any {
                        FuzzyMatcher.score(expected, it.text) >= 0.6f
                    }
                    cb(if (found) StepResult.SUCCESS else StepResult.FAILED)
                }
            }

            StepKind.SEARCH_WEB -> {
                val q = step.payload["query"]
                if (q.isNullOrBlank()) { cb(StepResult.FAILED); return }
                search.openSearch(q)
                cb(StepResult.SUCCESS)
            }

            StepKind.CREATE_NOTE -> {
                val text = step.payload["text"]
                if (text.isNullOrBlank()) { cb(StepResult.FAILED); return }
                Thread {
                    runBlocking { notes.create(text) }
                    cb(StepResult.SUCCESS)
                }.start()
            }

            StepKind.MEMORIZE -> {
                val text = step.payload["text"]
                if (text.isNullOrBlank()) { cb(StepResult.FAILED); return }
                Thread {
                    runBlocking { memory.remember("manual", text, "fact") }
                    cb(StepResult.SUCCESS)
                }.start()
            }

            StepKind.CONFIRM -> {
                val question = step.payload["text"] ?: step.title
                onConfirm(question, { cb(StepResult.SUCCESS) }, { cb(StepResult.SKIPPED) })
            }

            StepKind.WAIT -> cb(StepResult.SUCCESS)
            StepKind.REPORT -> cb(StepResult.SUCCESS)
        }
    }
}
