package com.nova.assistant.core

/**
 * Разрешает ссылки на контекст: местоимения, незавершённые действия,
 * уточняющие вопросы. Работает поверх [CommandParser].
 *
 * Пример: «открой телеграм» → «напиши ему я скоро буду».
 * Resolver подставит контакт и приложение из контекста диалога.
 */
class IntentResolver(private val parser: CommandParser) {

    fun resolve(raw: String, ctx: ConversationContext): ParseResult {
        val result = parser.parse(raw, ctx)
        if (result.intents.isEmpty()) {
            // Парсер не понял — пробуем догадаться по контексту.
            val guessed = guessFromContext(raw, ctx)
            return if (guessed != null) ParseResult(listOf(guessed)) else result
        }
        return ParseResult(result.intents.map { resolveOne(it, ctx) })
    }

    private fun resolveOne(intent: Intent, ctx: ConversationContext): Intent {
        if (intent.type != IntentType.UNKNOWN) return intent
        return guessFromContext(intent.raw, ctx) ?: intent
    }

    /** Пытается понять фразу через контекст прошлого хода. */
    private fun guessFromContext(raw: String, ctx: ConversationContext): Intent? {
        val norm = FuzzyMatcher.normalize(raw)
        if (norm.isEmpty()) return null

        // «напиши ему» / «напиши ей» — контакт из контекста, текст из продиктованного.
        if ((norm == "напиши ему" || norm == "напиши ей" || norm == "напиши им" || norm == "написать ему") &&
            ctx.lastContact != null && !ctx.lastDictated.isNullOrBlank()
        ) {
            return Intent(
                IntentType.SEND_MESSAGE, raw,
                contact = ctx.lastContact, text = ctx.lastDictated,
                confidence = 0.8f
            )
        }

        // «открой его» / «открой её» / «вернись в него»
        if ((norm == "открой его" || norm == "открой её" || norm == "открой ее" || norm == "вернись в него") &&
            ctx.lastAppName != null
        ) {
            return Intent(IntentType.OPEN_APP, raw, appName = ctx.lastAppName, confidence = 0.75f)
        }

        // «найди это» / «загугли это» / «найди это в гугле»
        if ((norm == "найди это" || norm == "загугли это" || norm == "найди это в гугле" || norm == "поищи это") &&
            !ctx.screenText.isNullOrBlank()
        ) {
            return Intent(IntentType.SEARCH_WEB, raw, query = ctx.screenText, confidence = 0.75f)
        }

        // «повтори» / «скажи ещё раз»
        if (norm == "повтори" || norm == "скажи еще раз" || norm == "повторите") {
            return Intent(IntentType.RECALL, raw, query = ctx.lastQuery, confidence = 0.6f)
        }

        return null
    }
}
