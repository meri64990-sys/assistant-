package com.nova.assistant.core

/** Уровень риска действия и решение: выполнять или спросить. */
enum class Risk { SAFE, CONFIRM, FORBIDDEN }

data class SafetyDecision(
    val risk: Risk,
    val reason: String
)

/**
 * Местный контроль безопасности. Работает без сети и без серверов:
 * классификация выполняется по типу намерения и ключевым словам.
 *
 * Правило: обратимые действия — сразу; необратимые/публичные — только
 * после явного подтверждения; опасные — запрещены всегда.
 */
object SafetyManager {

    private val FORBIDDEN_KEYWORDS = listOf(
        "оплати", "оплатить", "заплати", "платёж", "платеж", "переведи деньги",
        "перевод денег", "покупка", "купи", "оформи подписку", "подписку",
        "смени пароль", "пароль от", "удали все", "удали всё", "отформатируй",
        "сбрось настройки", "фабрика", "рут", "администратор", "банковский"
    )

    private val CONFIRM_KEYWORDS = listOf(
        "отправь", "отправляй", "напиши", "позвони", "набери",
        "удали", "удали заметку", "удали всё", "измени настройки", "включи передачу данных",
        "выключи", "перезагрузи", "выключи телефон", "опубликуй", "опубликовать"
    )

    fun classify(intent: Intent): SafetyDecision {
        val raw = FuzzyMatcher.normalize(intent.raw)

        // 1. Явно опасные действия — всегда запрещены (NOVA не может их выполнять).
        for (k in FORBIDDEN_KEYWORDS) {
            if (raw.contains(k)) {
                return SafetyDecision(Risk.FORBIDDEN, "действие «$k» запрещено: это необратимо и может причинить вред")
            }
        }

        // 2. Тип намерения по умолчанию.
        when (intent.type) {
            IntentType.SEND_MESSAGE, IntentType.CALL_CONTACT ->
                return SafetyDecision(Risk.CONFIRM, "требуется подтверждение: сообщение/звонок — это действие от вашего имени")
            IntentType.DELETE_NOTE ->
                return SafetyDecision(Risk.CONFIRM, "требуется подтверждение: заметка будет удалена")
            IntentType.OPEN_SUB_SETTINGS ->
                return SafetyDecision(Risk.CONFIRM, "требуется подтверждение: изменение системных настроек")
            IntentType.REMINDER, IntentType.TIMER ->
                return SafetyDecision(Risk.SAFE, "напоминание/таймер — обратимое локальное действие")
            else -> Unit
        }

        // 3. Ключевые слова в тексте.
        for (k in CONFIRM_KEYWORDS) {
            if (raw.contains(k)) {
                return SafetyDecision(Risk.CONFIRM, "похоже на действие «$k» — нужно подтверждение")
            }
        }

        return SafetyDecision(Risk.SAFE, "обратимое действие")
    }

    /** Короткий текст вопроса для подтверждения. */
    fun confirmationQuestion(intent: Intent): String = when (intent.type) {
        IntentType.SEND_MESSAGE -> "Отправить сообщение" + (intent.contact?.let { " $it" } ?: "") + " текстом «${intent.text}»?"
        IntentType.CALL_CONTACT -> "Позвонить: ${intent.contact ?: "контакт не распознан"}?"
        IntentType.DELETE_NOTE -> "Удалить заметку «${intent.note ?: ""}»?"
        IntentType.OPEN_SUB_SETTINGS -> "Изменить системную настройку «${intent.query ?: intent.raw}»?"
        else -> "Выполнить: ${intent.raw}?"
    }
}
