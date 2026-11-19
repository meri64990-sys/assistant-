package com.nova.assistant.core

/**
 * Локальный разбор голосовых команд.
 *
 * Принцип: никаких облачных моделей. Команда разбивается на части,
 * каждая часть классифицируется по словарю фраз с нечётким сопоставлением,
 * из неё извлекаются слоты (приложение, контакт, текст, запрос).
 *
 * Одна фраза может содержать несколько действий:
 *   «открой телеграм, найди Сашу и напиши, что я скоро буду»
 *   → OPEN_APP(telegram) + OPEN_CHAT(Саша) + SEND_MESSAGE(Саша, «я скоро буду»)
 *
 * Если ни одна команда не распознана — включается «мягкий» режим
 * с пониженным порогом, чтобы прощать ошибки произношения.
 */
class CommandParser(
    /** Имена контактов (из телефонной книги) — для «напиши Саше». */
    private val contacts: List<String> = emptyList(),
    /** Метки установленных приложений — для запасного сопоставления. */
    private val installedApps: List<String> = emptyList()
) {

    fun parse(raw: String, ctx: ConversationContext): ParseResult {
        val strict = parseInternal(raw, ctx, lenient = false)
        if (strict.intents.isNotEmpty()) return strict
        val soft = parseInternal(raw, ctx, lenient = true)
        return if (soft.intents.isNotEmpty()) soft else strict
    }

    private fun parseInternal(raw: String, ctx: ConversationContext, lenient: Boolean): ParseResult {
        var text = raw.trim()
        if (text.isEmpty()) {
            return ParseResult(emptyList(), "Скажите команду — например, «открой телеграм».")
        }
        text = stripWakeWords(text, lenient)
        if (text.isBlank()) {
            return ParseResult(
                emptyList(),
                "Слушаю. Что сделать? Например: «открой ютуб» или «запиши в заметках купить зарядку»."
            )
        }

        val intents = mutableListOf<Intent>()
        for (clause in splitClauses(text)) {
            val clauseIntents = parseClause(clause, ctx, lenient)
            intents += clauseIntents
            // Контекст между частями одной фразы: «найди Сашу и напиши ему …».
            for (i in clauseIntents) {
                when (i.type) {
                    IntentType.OPEN_CHAT, IntentType.SEND_MESSAGE -> ctx.lastContact = i.contact
                    IntentType.OPEN_APP -> ctx.lastAppName = i.appName
                    IntentType.CREATE_NOTE -> ctx.lastDictated = i.note
                    else -> Unit
                }
            }
        }

        val meaningful = intents.filter { it.type != IntentType.UNKNOWN }
        if (meaningful.isEmpty()) {
            val firstUnknown = intents.firstOrNull { it.clarification != null }?.clarification
            return ParseResult(emptyList(), firstUnknown ?: clarifyFor(text))
        }
        return ParseResult(intents.filter { it.type != IntentType.UNKNOWN })
    }

    // ------------------------------------------------------------------ //
    // Разделение фразы на части
    // ------------------------------------------------------------------ //

    private val CONNECTORS = Regex("(?i)\\s+(?:и|а|затем|потом|после этого)\\s+")

    private val ANCHORS = listOf(
        "откройте", "открой", "запустите", "запусти", "запускай", "зайди", "перейди",
        "включи", "напишите", "напиши", "отправьте", "отправь", "отправляй", "найди",
        "поищи", "запишите", "запиши", "запомни", "позвони", "набери", "прочитай",
        "посмотри", "покажи", "поставь", "напомни", "закрой", "выключи"
    )

    /** Запятая — разделитель, только если после неё идёт новая команда. */
    private val COMMA_ANCHOR = Regex(
        ",(?=\\s*(?:" + ANCHORS.joinToString("|") { Regex.escape(it) } + ")(?=\\s|$))"
    )

    private fun splitClauses(text: String): List<String> {
        val parts = mutableListOf<String>()
        for (chunk in text.split(CONNECTORS)) {
            for (c in chunk.split(COMMA_ANCHOR)) {
                val cc = c.trim()
                if (cc.isEmpty()) continue
                val pattern = Regex(
                    "(?i)(?<=^|[\\s,;])(" + ANCHORS.joinToString("|") { Regex.escape(it) } + ")(?=\\s|$)"
                )
                val matches = pattern.findAll(cc).toList()
                if (matches.isEmpty()) {
                    parts += cc
                    continue
                }
                var idx = 0
                for (m in matches) {
                    if (m.range.first > idx) {
                        val head = cc.substring(idx, m.range.first).trim()
                        if (head.isNotEmpty()) parts += head
                    }
                    idx = m.range.first
                }
                val tail = cc.substring(idx).trim()
                if (tail.isNotEmpty()) parts += tail
            }
        }
        return parts.filter { it.isNotBlank() }
    }

    private fun stripWakeWords(text: String, lenient: Boolean): String {
        var t = text.trim()
        var changed = true
        val threshold = if (lenient) 0.7f else 0.8f
        while (changed) {
            changed = false
            val norm = FuzzyMatcher.normalize(t)
            for (w in AliasDictionary.wakeWords.sortedByDescending { it.length }) {
                val wn = FuzzyMatcher.normalize(w)
                if (wn.isEmpty() || norm.length <= wn.length) continue
                if (FuzzyMatcher.similarity(norm.substring(0, wn.length), wn) >= threshold) {
                    val end = FuzzyMatcher.prefixEnd(t, wn.length)
                    t = t.substring(end).trim(' ', ',', '.', '!', '?')
                    changed = true
                    break
                }
            }
        }
        return t
    }

    // ------------------------------------------------------------------ //
    // Разбор одной части
    // ------------------------------------------------------------------ //

    private fun parseClause(clause: String, ctx: ConversationContext, lenient: Boolean): List<Intent> {
        val original = clause.trim()
        val norm = FuzzyMatcher.normalize(original)
        if (norm.isEmpty()) return emptyList()

        // Короткие команды целиком
        if (isWhole(norm, AliasDictionary.stopPhrases)) return listOf(Intent(IntentType.STOP, original, confidence = 1f))
        if (isWhole(norm, AliasDictionary.confirmPhrases)) return listOf(Intent(IntentType.CONFIRM, original, confidence = 1f, pending = ctx.pendingConfirmation))
        if (isWhole(norm, AliasDictionary.denyPhrases)) return listOf(Intent(IntentType.DENY, original, confidence = 1f, pending = ctx.pendingConfirmation))
        if (isWhole(norm, AliasDictionary.homePhrases)) return listOf(Intent(IntentType.GO_HOME, original, confidence = 1f))
        if (isWhole(norm, AliasDictionary.backPhrases)) return listOf(Intent(IntentType.GO_BACK, original, confidence = 1f))
        if (isWhole(norm, AliasDictionary.settingsPhrases)) return listOf(Intent(IntentType.OPEN_SETTINGS, original, confidence = 1f))

        // Экран: описать ситуацию / прочитать
        val describe = matchPrefix(norm, AliasDictionary.describeScreenPhrases, lenient)
        if (describe != null) {
            return listOf(Intent(IntentType.DESCRIBE_SCREEN, original, query = remainderOf(original, describe).ifBlank { null }, confidence = 0.9f))
        }
        val read = matchPrefix(norm, AliasDictionary.readScreenPhrases, lenient)
        if (read != null) {
            return listOf(Intent(IntentType.READ_SCREEN, original, query = remainderOf(original, read).ifBlank { null }, confidence = 0.9f))
        }

        // Интернет-поиск
        val search = matchPrefix(norm, AliasDictionary.searchPhrases, lenient)
        if (search != null) {
            var q = remainderOf(original, search).trim()
            if (q.isEmpty() || q.equals("это", true) || q.equals("эту ошибку", true) ||
                q.equals("это сообщение", true) || q.equals("то что на экране", true)
            ) {
                q = ctx.screenText ?: ctx.lastText ?: ctx.lastQuery ?: ""
            }
            if (q.isBlank()) {
                return listOf(Intent(IntentType.UNKNOWN, original, confidence = 0.3f, clarification = "Что найти? Например: «найди в гугле, почему телефон греется»."))
            }
            return listOf(Intent(IntentType.SEARCH_WEB, original, query = q, confidence = 0.88f))
        }

        // Память
        val memorize = matchPrefix(norm, AliasDictionary.memorizePhrases, lenient)
        if (memorize != null) {
            var v = remainderOf(original, memorize).trim()
            if (v.isEmpty() || v.equals("это", true)) v = ctx.lastDictated ?: ctx.screenText ?: ""
            if (v.isBlank()) return listOf(Intent(IntentType.UNKNOWN, original, confidence = 0.3f, clarification = "Что запомнить?"))
            return listOf(Intent(IntentType.MEMORIZE, original, text = v, confidence = 0.9f))
        }
        val recall = matchPrefix(norm, AliasDictionary.recallPhrases, lenient)
        if (recall != null) {
            return listOf(Intent(IntentType.RECALL, original, query = remainderOf(original, recall).ifBlank { null }, confidence = 0.88f))
        }
        val forget = matchPrefix(norm, AliasDictionary.forgetPhrases, lenient)
        if (forget != null) {
            return listOf(Intent(IntentType.FORGET, original, text = remainderOf(original, forget).ifBlank { null }, confidence = 0.9f))
        }

        // Таймер и напоминание
        val timer = matchPrefix(norm, AliasDictionary.timerPhrases, lenient)
        if (timer != null) {
            return listOf(Intent(IntentType.TIMER, original, text = remainderOf(original, timer).ifBlank { "5 минут" }, confidence = 0.9f))
        }
        val reminder = matchPrefix(norm, AliasDictionary.reminderPhrases, lenient)
        if (reminder != null) {
            return listOf(Intent(IntentType.REMINDER, original, text = remainderOf(original, reminder).ifBlank { null }, confidence = 0.9f))
        }

        // Список и поиск заметок
        val noteList = matchPrefix(norm, AliasDictionary.noteListPhrases, lenient)
        if (noteList != null) {
            val r = remainderOf(original, noteList)
            return listOf(
                Intent(
                    if (r.isBlank()) IntentType.LIST_NOTES else IntentType.FIND_NOTE,
                    original, query = r.ifBlank { null }, confidence = 0.88f
                )
            )
        }

        // Создание заметки (но «запиши Саше …» — это сообщение)
        val noteCreate = matchPrefix(norm, AliasDictionary.notePhrases, lenient)
        if (noteCreate != null) {
            val content = remainderOf(original, noteCreate).trim()
            if (!looksLikeContact(content, ctx)) {
                var c = content
                if (c.isEmpty() || c.equals("это", true) || c.equals("это сообщение", true)) {
                    c = ctx.lastDictated ?: ctx.screenText ?: ""
                }
                if (c.isBlank()) {
                    return listOf(Intent(IntentType.UNKNOWN, original, confidence = 0.3f, clarification = "Что записать? Например: «запиши в заметках купить зарядку»."))
                }
                return listOf(Intent(IntentType.CREATE_NOTE, original, note = c, confidence = 0.9f))
            }
        }

        // Звонок
        val call = matchPrefix(norm, AliasDictionary.callPhrases, lenient)
        if (call != null) {
            val r = remainderOf(original, call).trim()
            val contact = resolveContact(r, ctx)
            if (contact.isNullOrBlank()) {
                return listOf(Intent(IntentType.UNKNOWN, original, confidence = 0.3f, clarification = "Кому позвонить? Например: «позвони маме»."))
            }
            return listOf(Intent(IntentType.CALL_CONTACT, original, contact = contact, confidence = 0.88f))
        }

        // Сообщение
        val send = matchPrefix(norm, AliasDictionary.sendPhrases, lenient)
        if (send != null) {
            val r = remainderOf(original, send).trim()
            val (contact, rawText) = extractContactText(r, ctx)
            var text = rawText?.trim().orEmpty()
            // «напиши, что я скоро буду» → текст «я скоро буду».
            if (text.lowercase().startsWith("что ") && text.length > 5) text = text.substring(4).trim()
            if (text.isEmpty()) {
                return listOf(Intent(IntentType.UNKNOWN, original, confidence = 0.3f, clarification = "Что написать?"))
            }
            if (contact.isNullOrBlank()) {
                return listOf(Intent(IntentType.UNKNOWN, original, confidence = 0.3f, clarification = "Кому написать? Например: «напиши Саше, я скоро буду»."))
            }
            return listOf(Intent(IntentType.SEND_MESSAGE, original, contact = contact, text = text, confidence = 0.88f))
        }

        // Чат / поиск контакта
        val chat = matchPrefix(norm, AliasDictionary.openChatPhrases, lenient)
        if (chat != null) {
            val r = remainderOf(original, chat).trim()
            if (r.isNotBlank()) return listOf(Intent(IntentType.OPEN_CHAT, original, contact = r, confidence = 0.88f))
        }
        val findContact = matchPrefix(norm, listOf("найди в контактах", "покажи контакт", "найди"), lenient)
        if (findContact != null) {
            val r = remainderOf(original, findContact).trim()
            if (r.isNotBlank()) {
                val m = FuzzyMatcher.bestMatch(r, contacts, 0.62f)
                return listOf(Intent(IntentType.OPEN_CHAT, original, contact = m?.value ?: r, confidence = m?.score ?: 0.7f))
            }
        }

        // Игра и обучение
        val game = matchPrefix(norm, AliasDictionary.gamePhrases, lenient)
        if (game != null) {
            return listOf(Intent(IntentType.GAME_HELP, original, query = remainderOf(original, game).ifBlank { null }, confidence = 0.88f))
        }
        val edu = matchPrefix(norm, AliasDictionary.educationPhrases, lenient)
        if (edu != null) {
            return listOf(Intent(IntentType.EDUCATION_HELP, original, query = remainderOf(original, edu).ifBlank { null }, confidence = 0.88f))
        }

        // Сайт
        val url = matchPrefix(norm, listOf("открой сайт", "зайди на сайт", "открой в браузере"), lenient)
        if (url != null) {
            val r = remainderOf(original, url).trim()
            if (r.isNotBlank()) return listOf(Intent(IntentType.OPEN_URL, original, query = r, confidence = 0.85f))
        }

        // Открыть приложение
        val open = matchPrefix(norm, AliasDictionary.openPhrases, lenient)
        if (open != null) {
            val r = remainderOf(original, open).trim()
            if (r.isNotBlank()) {
                val rn = FuzzyMatcher.normalize(r)
                if (rn.startsWith(FuzzyMatcher.normalize("настройки")) || rn.startsWith(FuzzyMatcher.normalize("параметры"))) {
                    return listOf(Intent(IntentType.OPEN_SUB_SETTINGS, original, query = r, confidence = 0.9f))
                }
                if (isSystemToggle(rn)) {
                    return listOf(Intent(IntentType.OPEN_SUB_SETTINGS, original, query = r, confidence = 0.88f))
                }
                val resolved = resolveApp(r)
                if (resolved != null) {
                    return listOf(Intent(IntentType.OPEN_APP, original, appName = resolved.first, confidence = resolved.second))
                }
                val m = FuzzyMatcher.bestMatch(r, installedApps, 0.6f)
                if (m != null) return listOf(Intent(IntentType.OPEN_APP, original, appName = m.value, confidence = m.score))
                return listOf(
                    Intent(
                        IntentType.UNKNOWN, original, confidence = 0.35f,
                        clarification = "Не нашла приложение «$r». Повторите название или добавьте приложение в список."
                    )
                )
            }
        }

        // Вопрос без явного «найди» — скорее всего поисковый запрос
        val qWords = listOf("что такое", "почему", "зачем", "как ", "сколько", "где", "когда", "кто такой", "что будет")
        if (qWords.any { norm.startsWith(it) }) {
            return listOf(Intent(IntentType.SEARCH_WEB, original, query = original, confidence = 0.7f))
        }

        return listOf(
            Intent(
                IntentType.UNKNOWN, original, confidence = 0.2f,
                clarification = "Не поняла команду. Попробуйте: «открой телеграм», «запиши в заметках …», «найди в гугле …»."
            )
        )
    }

    // ------------------------------------------------------------------ //
    // Вспомогательные функции
    // ------------------------------------------------------------------ //

    /** Слово/фраза целиком совпадает с командой. */
    private fun isWhole(norm: String, phrases: List<String>, threshold: Float = 0.86f): Boolean {
        for (p in phrases) {
            val pn = FuzzyMatcher.normalize(p)
            if (pn.isEmpty() || norm.length > pn.length + 4) continue
            if (FuzzyMatcher.similarity(norm, pn) >= threshold) return true
        }
        return false
    }

    /**
     * Самый длинный префикс фразы, подходящий к началу команды.
     * Строгий режим требует совпадения первого символа — иначе «напомни»
     * ошибочно принялось бы за «запомни».
     */
    private fun matchPrefix(norm: String, phrases: List<String>, lenient: Boolean, threshold: Float = 0.9f): String? {
        val th = if (lenient) minOf(threshold, 0.8f) else threshold
        var best: String? = null
        var bestLen = 0
        for (p in phrases) {
            val pn = FuzzyMatcher.normalize(p)
            if (pn.isEmpty() || norm.length < pn.length - 1) continue
            val window = norm.substring(0, minOf(pn.length, norm.length))
            val s = FuzzyMatcher.similarity(window, pn)
            val firstOk = window.firstOrNull() == pn.firstOrNull()
            if (s >= th && (firstOk || s >= 0.97f) && pn.length > bestLen) {
                best = p
                bestLen = pn.length
            }
        }
        return best
    }

    private fun remainderOf(original: String, phrase: String): String {
        val pn = FuzzyMatcher.normalize(phrase)
        val end = FuzzyMatcher.prefixEnd(original, pn.length)
        return original.substring(minOf(end, original.length)).trim(' ', ',', '.', '!', '?', '-', ':', ';')
    }

    /** Системные переключатели, которые открываются как разделы настроек. */
    private fun isSystemToggle(norm: String): Boolean {
        val toggles = listOf(
            "wi-fi", "wifi", "вайфай", "блютуз", "bluetooth", "экран", "звук", "громкость",
            "батарея", "память", "уведомления", "приложения", "дисплей", "обои", "блокировка",
            "безопасность", "местоположение", "геоданные", "язык", "дата и время", "сеть",
            "режим полета", "модем", "разработчик"
        ).map { FuzzyMatcher.normalize(it) }
        return toggles.any { norm.startsWith(it) }
    }

    /** Сопоставляет название приложения с локальным словарём и установленными приложениями. */
    fun resolveApp(name: String): Pair<String, Float>? {
        val norm = FuzzyMatcher.normalize(name)
        if (norm.isEmpty()) return null
        var bestCanon: String? = null
        var bestScore = 0f
        for ((canon, aliases) in AliasDictionary.apps) {
            for (a in aliases) {
                val s = FuzzyMatcher.scoreTranslit(norm, a)
                if (s > bestScore) {
                    bestScore = s
                    bestCanon = canon
                }
            }
        }
        if (bestCanon != null && bestScore >= 0.66f) return bestCanon to bestScore
        return null
    }

    /** «Саше», «маме», «по номеру 12345» → контакт. */
    private fun resolveContact(text: String, ctx: ConversationContext): String? {
        val t = text.trim()
        if (t.isEmpty()) return null
        if (t.contains("номер") || t.any { it.isDigit() }) return t
        val m = FuzzyMatcher.bestMatch(t, contacts, 0.72f)
        if (m != null) return m.value
        if (ctx.lastContact != null && FuzzyMatcher.score(t, ctx.lastContact!!) >= 0.72f) return ctx.lastContact
        return null
    }

    /** Разделяет «напиши Саше я скоро буду» на контакт и текст. */
    private fun extractContactText(remainder: String, ctx: ConversationContext): Pair<String?, String?> {
        val r = remainder.trim()
        if (r.isEmpty()) return null to null
        val lower = FuzzyMatcher.normalize(r)
        if (lower == "ему" || lower == "ей" || lower == "им") {
            return ctx.lastContact to null
        }
        val words = r.split(" ")
        for (len in 2 downTo 1) {
            if (words.size > len) {
                val cand = words.take(len).joinToString(" ")
                val m = FuzzyMatcher.bestMatch(cand, contacts, 0.72f)
                if (m != null) return m.value to words.drop(len).joinToString(" ").trim()
            }
        }
        if (ctx.lastContact != null) return ctx.lastContact to r
        return null to r
    }

    private fun looksLikeContact(text: String, ctx: ConversationContext): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return false
        val words = t.split(" ")
        for (len in 2 downTo 1) {
            if (words.size > len) {
                val cand = words.take(len).joinToString(" ")
                if (FuzzyMatcher.bestMatch(cand, contacts, 0.72f) != null) return true
            }
        }
        if (ctx.lastContact != null && FuzzyMatcher.score(words.first(), ctx.lastContact!!) >= 0.72f) return true
        return false
    }

    private fun clarifyFor(text: String): String {
        val n = FuzzyMatcher.normalize(text)
        return if (n.length <= 2) {
            "Слушаю. Скажите, что сделать."
        } else {
            "Не поняла команду «$text». Попробуйте: «открой телеграм», «запиши в заметках …», «найди в гугле …»."
        }
    }
}
