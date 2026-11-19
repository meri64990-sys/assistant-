package com.nova.assistant.core

/** Типы шагов, которые умеет выполнять исполнитель. */
enum class StepKind {
    OPEN_APP,       // запустить приложение
    OPEN_URL,       // открыть ссылку в браузере
    OPEN_SETTINGS,  // системные настройки
    HOME, BACK,
    NAVIGATE,       // перейти в раздел (по названию)
    FIND_ELEMENT,   // найти элемент на экране
    TAP_ELEMENT,    // нажать элемент
    TYPE_TEXT,      // ввести текст
    SWIPE,          // прокрутить
    VERIFY_SCREEN,  // проверить, что экран изменился нужным образом
    READ_SCREEN,    // прочитать содержимое экрана
    SEARCH_WEB,     // поиск в интернете
    CREATE_NOTE,    // создать заметку
    MEMORIZE,       // сохранить в память
    CONFIRM,        // спросить подтверждение
    WAIT,           // пауза
    REPORT          // сообщить результат
}

/** Один шаг плана. */
data class TaskStep(
    val id: Int,
    val title: String,
    val kind: StepKind,
    val payload: Map<String, String?> = emptyMap(),
    val optional: Boolean = false,
    val retryable: Boolean = true
)

/** План задачи. */
data class TaskPlan(
    val goal: String,
    val steps: List<TaskStep>,
    val createdAt: Long,
    val domain: String
) {
    val total: Int get() = steps.size
}

/**
 * Локальный планировщик: цель → последовательность проверяемых шагов.
 * Без облачных моделей: план строится из распознанных намерений и
 * доменных шаблонов (мессенджер, учёба, игра).
 */
object TaskPlanner {

    const val DOMAIN_MESSENGER = "messenger"
    const val DOMAIN_EDUCATION = "education"
    const val DOMAIN_GAME = "game"
    const val DOMAIN_MIXED = "mixed"
    const val DOMAIN_SIMPLE = "simple"

    fun plan(goal: String, intents: List<Intent>, ctx: ConversationContext, now: Long = System.currentTimeMillis()): TaskPlan {
        val domain = detectDomain(goal, intents)
        val steps = when (domain) {
            DOMAIN_EDUCATION -> educationSteps(goal, intents)
            DOMAIN_GAME -> gameSteps(goal, intents)
            DOMAIN_MESSENGER -> messengerSteps(goal, intents)
            DOMAIN_MIXED -> mixedSteps(goal, intents)
            else -> simpleSteps(goal, intents)
        }
        return TaskPlan(goal, steps, now, domain)
    }

    fun detectDomain(goal: String, intents: List<Intent>): String {
        val g = FuzzyMatcher.normalize(goal)
        if (intents.any { it.type == IntentType.EDUCATION_HELP } ||
            g.contains("мэш") || g.contains("меш") || g.contains("тест") || g.contains("задание") ||
            g.contains("урок") || g.contains("дз") || g.contains("домашн") || g.contains("расписание") ||
            g.contains("московская электронная школа")
        ) return DOMAIN_EDUCATION
        if (intents.any { it.type == IntentType.GAME_HELP } ||
            g.contains("игровой") || g.contains("уровень в игре") || g.contains("пройти уровень") ||
            g.contains("прохождение") || g.contains("помоги с игрой")
        ) return DOMAIN_GAME
        if (intents.count { it.type == IntentType.OPEN_APP || it.type == IntentType.OPEN_CHAT || it.type == IntentType.SEND_MESSAGE } >= 2 ||
            (intents.any { it.type == IntentType.SEND_MESSAGE } && intents.any { it.type == IntentType.OPEN_APP })
        ) return DOMAIN_MESSENGER
        if (intents.size >= 2) return DOMAIN_MIXED
        return DOMAIN_SIMPLE
    }

    // ------------------------------------------------------------------ //

    private fun simpleSteps(goal: String, intents: List<Intent>): List<TaskStep> {
        val steps = mutableListOf<TaskStep>()
        var id = 1
        for (i in intents) {
            when (i.type) {
                IntentType.OPEN_APP -> steps += step(id++, "Открыть приложение «${i.appName}»", StepKind.OPEN_APP, mapOf("app" to i.appName))
                IntentType.OPEN_SETTINGS -> steps += step(id++, "Открыть настройки", StepKind.OPEN_SETTINGS)
                IntentType.OPEN_SUB_SETTINGS -> steps += step(id++, "Открыть настройки: ${i.query}", StepKind.OPEN_SETTINGS, mapOf("section" to i.query))
                IntentType.GO_HOME -> steps += step(id++, "На главный экран", StepKind.HOME)
                IntentType.GO_BACK -> steps += step(id++, "Назад", StepKind.BACK)
                IntentType.SEARCH_WEB -> steps += step(id++, "Искать: ${i.query}", StepKind.SEARCH_WEB, mapOf("query" to i.query))
                IntentType.READ_SCREEN -> steps += step(id++, "Прочитать экран", StepKind.READ_SCREEN, mapOf("query" to i.query))
                IntentType.DESCRIBE_SCREEN -> steps += step(id++, "Описать ситуацию на экране", StepKind.READ_SCREEN, mapOf("explain" to "true"))
                IntentType.CREATE_NOTE -> steps += step(id++, "Создать заметку «${i.note}»", StepKind.CREATE_NOTE, mapOf("text" to i.note))
                IntentType.MEMORIZE -> steps += step(id++, "Сохранить в память", StepKind.MEMORIZE, mapOf("text" to i.text))
                IntentType.OPEN_URL -> steps += step(id++, "Открыть сайт ${i.query}", StepKind.OPEN_URL, mapOf("url" to i.query))
                else -> Unit
            }
        }
        if (steps.isEmpty()) steps += step(id, "Выполнить: $goal", StepKind.REPORT)
        return steps
    }

    private fun messengerSteps(goal: String, intents: List<Intent>): List<TaskStep> {
        val steps = mutableListOf<TaskStep>()
        var id = 1
        val open = intents.firstOrNull { it.type == IntentType.OPEN_APP }
        val chat = intents.firstOrNull { it.type == IntentType.OPEN_CHAT }
        val send = intents.firstOrNull { it.type == IntentType.SEND_MESSAGE }

        if (open != null) steps += step(id++, "Открыть ${open.appName}", StepKind.OPEN_APP, mapOf("app" to open.appName))
        if (chat != null) {
            steps += step(id++, "Найти чат «${chat.contact}»", StepKind.FIND_ELEMENT, mapOf("text" to chat.contact))
            steps += step(id++, "Нажать на чат «${chat.contact}»", StepKind.TAP_ELEMENT, mapOf("text" to chat.contact))
        }
        if (send != null) {
            if (chat == null) {
                steps += step(id++, "Найти чат «${send.contact}»", StepKind.FIND_ELEMENT, mapOf("text" to send.contact))
                steps += step(id++, "Открыть чат «${send.contact}»", StepKind.TAP_ELEMENT, mapOf("text" to send.contact))
            }
            steps += step(id++, "Проверить, что открыт чат «${send.contact}»", StepKind.VERIFY_SCREEN, mapOf("contains" to send.contact), optional = true)
            steps += step(id++, "Ввести текст: ${send.text}", StepKind.TYPE_TEXT, mapOf("text" to send.text))
            steps += step(id++, "Подтвердить отправку", StepKind.CONFIRM, mapOf("text" to send.text, "contact" to send.contact))
        }
        steps += step(id++, "Сообщить результат", StepKind.REPORT)
        return steps
    }

    private fun educationSteps(goal: String, intents: List<Intent>): List<TaskStep> {
        val g = goal
        val open = intents.firstOrNull { it.type == IntentType.OPEN_APP }
        val steps = mutableListOf<TaskStep>()
        var id = 1
        steps += step(id++, "Открыть образовательное приложение", StepKind.OPEN_APP, mapOf("app" to (open?.appName ?: "мэш")))
        steps += step(id++, "Проверить расписание", StepKind.READ_SCREEN, mapOf("contains" to "расписание"), optional = true)
        steps += step(id++, "Найти следующий урок", StepKind.FIND_ELEMENT, mapOf("text" to "следующ"))
        steps += step(id++, "Определить предмет", StepKind.READ_SCREEN)
        steps += step(id++, "Перейти к заданиям предмета", StepKind.TAP_ELEMENT, mapOf("text" to "задани"))
        steps += step(id++, "Найти нужное задание/тест", StepKind.FIND_ELEMENT, mapOf("text" to "тест"))
        steps += step(id++, "Открыть задание", StepKind.TAP_ELEMENT, mapOf("text" to "тест"))
        steps += step(id++, "Прочитать вопросы", StepKind.READ_SCREEN)
        steps += step(id++, "Поискать информацию по теме", StepKind.SEARCH_WEB, mapOf("query" to g), optional = true)
        steps += step(id++, "Сопоставить источники и подготовить ответы", StepKind.REPORT)
        steps += step(id++, "Проверить результат и объяснить решение", StepKind.VERIFY_SCREEN)
        steps += step(id++, "Сообщить пользователю о завершении", StepKind.REPORT)
        return steps
    }

    private fun gameSteps(goal: String, intents: List<Intent>): List<TaskStep> {
        val steps = mutableListOf<TaskStep>()
        var id = 1
        steps += step(id++, "Определить игру по экрану", StepKind.READ_SCREEN, mapOf("explain" to "game"))
        steps += step(id++, "Описать видимые элементы и цель", StepKind.READ_SCREEN)
        steps += step(id++, "Предложить следующее действие", StepKind.REPORT)
        steps += step(id++, "Выполнить действие после подтверждения", StepKind.CONFIRM)
        steps += step(id++, "Проверить изменение на экране", StepKind.VERIFY_SCREEN)
        steps += step(id++, "Скорректировать план при неудаче", StepKind.REPORT, optional = true)
        return steps
    }

    private fun mixedSteps(goal: String, intents: List<Intent>): List<TaskStep> {
        val steps = mutableListOf<TaskStep>()
        var id = 1
        for (i in intents) {
            when (i.type) {
                IntentType.OPEN_APP -> steps += step(id++, "Открыть ${i.appName}", StepKind.OPEN_APP, mapOf("app" to i.appName))
                IntentType.OPEN_CHAT -> {
                    steps += step(id++, "Найти чат «${i.contact}»", StepKind.FIND_ELEMENT, mapOf("text" to i.contact))
                    steps += step(id++, "Открыть чат «${i.contact}»", StepKind.TAP_ELEMENT, mapOf("text" to i.contact))
                }
                IntentType.SEND_MESSAGE -> {
                    steps += step(id++, "Ввести текст: ${i.text}", StepKind.TYPE_TEXT, mapOf("text" to i.text))
                    steps += step(id++, "Подтвердить отправку", StepKind.CONFIRM, mapOf("text" to i.text))
                }
                IntentType.CREATE_NOTE -> steps += step(id++, "Создать заметку", StepKind.CREATE_NOTE, mapOf("text" to i.note))
                IntentType.SEARCH_WEB -> steps += step(id++, "Искать: ${i.query}", StepKind.SEARCH_WEB, mapOf("query" to i.query))
                IntentType.READ_SCREEN -> steps += step(id++, "Прочитать экран", StepKind.READ_SCREEN)
                IntentType.MEMORIZE -> steps += step(id++, "Сохранить в память", StepKind.MEMORIZE, mapOf("text" to i.text))
                else -> Unit
            }
        }
        steps += step(id++, "Сообщить результат", StepKind.REPORT)
        return steps
    }

    private fun step(id: Int, title: String, kind: StepKind, payload: Map<String, String?> = emptyMap(), optional: Boolean = false): TaskStep =
        TaskStep(id, title, kind, payload, optional)
}

/** Простое кодирование плана для хранения в истории (без внешних зависимостей). */
object TaskPlanCodec {
    fun encode(plan: TaskPlan): String = buildString {
        append(plan.domain).append('|').append(plan.createdAt).append('|')
        append(plan.goal.replace("|", " ")).append('|')
        plan.steps.forEachIndexed { i, s ->
            if (i > 0) append('~')
            append(s.id).append(':').append(s.kind.name).append(':').append(s.optional).append(':')
            append(s.title.replace("|", " ").replace("~", " "))
            s.payload.forEach { (k, v) ->
                append(':').append(k).append('=').append((v ?: "").replace("|", " ").replace("~", " ").replace(":", " "))
            }
        }
    }

    fun decode(raw: String): TaskPlan? {
        return try {
            val p = raw.split('|')
            if (p.size < 4) return null
            val domain = p[0]
            val created = p[1].toLongOrNull() ?: return null
            val goal = p[2]
            val steps = mutableListOf<TaskStep>()
            for (chunk in p[3].split('~')) {
                val parts = chunk.split(':')
                if (parts.size < 4) continue
                val id = parts[0].toIntOrNull() ?: continue
                val kind = runCatching { StepKind.valueOf(parts[1]) }.getOrNull() ?: continue
                val optional = parts[2].toBooleanStrictOrNull() ?: false
                val title = parts[3]
                val payload = mutableMapOf<String, String?>()
                for (i in 4 until parts.size) {
                    val kv = parts[i].split('=', limit = 2)
                    if (kv.size == 2) payload[kv[0]] = kv[1]
                }
                steps += TaskStep(id, title, kind, payload, optional)
            }
            TaskPlan(goal, steps, created, domain)
        } catch (e: Exception) {
            null
        }
    }
}
