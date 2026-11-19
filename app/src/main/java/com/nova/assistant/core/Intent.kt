package com.nova.assistant.core

/** Типы намерений, которые понимает локальный обработчик NOVA. */
enum class IntentType {
    OPEN_APP,            // «открой телеграм»
    OPEN_SETTINGS,       // «открой настройки» / «зайди в настройки»
    OPEN_SUB_SETTINGS,   // «включи wifi», «открой настройки экрана»
    GO_HOME,             // «на главный экран»
    GO_BACK,             // «назад»
    SEARCH_WEB,          // «найди в гугле …»
    READ_SCREEN,         // «посмотри на экран», «что здесь написано»
    DESCRIBE_SCREEN,     // «что мне делать», «объясни ошибку»
    CREATE_NOTE,         // «запиши в заметках …»
    LIST_NOTES,          // «покажи заметки»
    FIND_NOTE,           // «найди заметку про …»
    DELETE_NOTE,         // «удали заметку …»
    SEND_MESSAGE,        // «напиши Саше …»
    CALL_CONTACT,        // «позвони маме»
    OPEN_CHAT,           // «открой чат с Сашей», «найди Сашу»
    MEMORIZE,            // «запомни, что …»
    RECALL,              // «что я говорил», «вспомни»
    FORGET,              // «забудь …»
    GAME_HELP,           // «помоги пройти уровень»
    EDUCATION_HELP,      // «разберись с заданием», «найди тест»
    TIMER,               // «поставь таймер на 5 минут»
    REMINDER,            // «напомни мне …»
    STOP,                // «стоп», «хватит»
    CONFIRM,             // «да», «подтверди», «отправляй»
    DENY,                // «нет», «отмена»
    OPEN_URL,            // «открой сайт …»
    UNKNOWN              // не удалось распознать
}

/**
 * Одно намерение. Все поля опциональны: наполнение зависит от типа.
 */
data class Intent(
    val type: IntentType,
    val raw: String,
    val appName: String? = null,
    val appPackage: String? = null,
    val text: String? = null,
    val contact: String? = null,
    val query: String? = null,
    val note: String? = null,
    val confidence: Float = 0f,
    val clarification: String? = null,
    /** Для CONFIRM/DENY — намерение, которое подтверждаем/отклоняем. */
    val pending: Intent? = null
) {
    val isSafe: Boolean get() = SafetyManager.classify(this).risk == Risk.SAFE
}

/** Результат разбора одной голосовой фразы. */
data class ParseResult(
    val intents: List<Intent>,
    /** Если не удалось понять — короткий уточняющий вопрос. */
    val clarification: String? = null
)
