package com.nova.assistant.tasks

import com.nova.assistant.NovaApp
import com.nova.assistant.core.ConversationContext
import com.nova.assistant.core.Intent
import com.nova.assistant.core.StepKind
import com.nova.assistant.core.TaskPlan
import com.nova.assistant.core.TaskPlanner
import com.nova.assistant.core.TaskStep
import com.nova.assistant.device.AccessibilityController
import com.nova.assistant.overlay.CharacterState
import kotlinx.coroutines.runBlocking

/**
 * Автономный агент: получает цель, строит план, выполняет шаги по очереди,
 * проверяет результат, повторяет неудачные действия ограниченное число раз,
 * меняет стратегию (прокрутка) и останавливается по команде «Стоп».
 *
 * Прогресс сохраняется в историю задач, поэтому задачу можно продолжить.
 */
class TaskAgent(
    private val executor: TaskExecutor,
    private val history: com.nova.assistant.tasks.TaskHistory
) {

    interface Callbacks {
        fun onState(state: CharacterState) {}
        fun onProgress(step: TaskStep, index: Int, total: Int) {}
        fun onMessage(text: String) {}
        fun onConfirm(question: String, onYes: () -> Unit, onNo: () -> Unit) {}
        fun onDone(report: String) {}
        fun onError(message: String) {}
    }

    private var plan: TaskPlan? = null
    private var index = 0
    @Volatile private var stopped = false
    private var recordId = -1L
    private val attempts = HashMap<Int, Int>()

    fun isRunning(): Boolean = plan != null && !stopped

    fun run(goal: String, intents: List<Intent>, callbacks: Callbacks) {
        if (isRunning()) {
            callbacks.onError("Я уже занят другой задачей. Скажите «стоп», чтобы начать новую.")
            return
        }
        val ctx = NovaApp.conversation ?: ConversationContext()
        val p = TaskPlanner.plan(goal, intents, ctx)
        plan = p
        index = 0
        stopped = false
        attempts.clear()
        callbacks.onState(CharacterState.THINK_HARD)
        callbacks.onMessage("Цель поняла. Шагов в плане: ${p.steps.size}.")
        Thread {
            recordId = runBlocking { history.start(p) }
            next(callbacks)
        }.start()
    }

    fun stop(callbacks: Callbacks) {
        stopped = true
        finish("Остановлено", "stopped", callbacks)
    }

    private fun next(callbacks: Callbacks) {
        val p = plan ?: return
        if (stopped) { finish("Остановлено", "stopped", callbacks); return }
        if (index >= p.steps.size) { finish("Готово", "done", callbacks); return }
        val step = p.steps[index]
        callbacks.onProgress(step, index + 1, p.steps.size)
        callbacks.onState(stateFor(step.kind))
        executeStep(step, callbacks)
    }

    private fun executeStep(step: TaskStep, callbacks: Callbacks) {
        executor.execute(
            step,
            { question, yes, no -> callbacks.onConfirm(question, yes, no) }
        ) { result ->
            when (result) {
                StepResult.SUCCESS -> { attempts.remove(step.id); index++; next(callbacks) }
                StepResult.SKIPPED -> { index++; next(callbacks) }
                StepResult.NEEDS_CONFIRM -> Unit
                StepResult.FAILED -> handleFailure(step, callbacks)
            }
        }
    }

    private fun handleFailure(step: TaskStep, callbacks: Callbacks) {
        val n = (attempts[step.id] ?: 0) + 1
        attempts[step.id] = n
        if (n >= 3 || !step.retryable) {
            if (step.optional) {
                index++
                next(callbacks)
            } else {
                finish("Не удалось выполнить шаг «${step.title}»", "failed", callbacks)
            }
            return
        }
        callbacks.onMessage("Не вышло: ${step.title}. Пробую другой способ (попытка $n).")
        if (n == 2) {
            // Смена стратегии: прокрутить экран и попробовать снова.
            AccessibilityController.swipe("up") { executeStep(step, callbacks) }
        } else {
            executeStep(step, callbacks)
        }
    }

    private fun finish(report: String, status: String, callbacks: Callbacks) {
        val p = plan
        plan = null
        if (p != null && recordId >= 0) {
            Thread {
                runBlocking { history.update(recordId, status, report) }
            }.start()
        }
        callbacks.onState(if (status == "done") CharacterState.DONE else CharacterState.ERROR)
        callbacks.onDone(report)
    }

    private fun stateFor(kind: StepKind): CharacterState = when (kind) {
        StepKind.OPEN_APP, StepKind.OPEN_URL, StepKind.OPEN_SETTINGS, StepKind.TAP_ELEMENT,
        StepKind.TYPE_TEXT, StepKind.SWIPE, StepKind.HOME, StepKind.BACK,
        StepKind.NAVIGATE -> CharacterState.ACTING
        StepKind.SEARCH_WEB -> CharacterState.SEARCHING
        StepKind.READ_SCREEN, StepKind.VERIFY_SCREEN -> CharacterState.READING
        StepKind.CONFIRM -> CharacterState.CONFIRM
        StepKind.CREATE_NOTE, StepKind.MEMORIZE -> CharacterState.ACTING
        StepKind.FIND_ELEMENT -> CharacterState.READING
        StepKind.WAIT, StepKind.REPORT -> CharacterState.THINKING
    }
}
