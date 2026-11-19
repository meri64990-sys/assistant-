package com.nova.assistant.core

/** Локальный поиск и фильтрация заметок. Без сети и без облака. */
object NotesQuery {

    /** Релевантность: совпадение в заголовке важнее, чем в тексте. */
    fun search(notes: List<Note>, query: String): List<Note> {
        val q = FuzzyMatcher.normalize(query)
        if (q.isEmpty()) return notes.sortedByDescending { it.updatedAt }
        return notes.mapNotNull { n ->
            val titleScore = FuzzyMatcher.score(q, n.title)
            val contentScore = FuzzyMatcher.score(q, n.content) * 0.8f
            val wordScore = bestWordScore(q, n.title + " " + n.content)
            val s = maxOf(titleScore, contentScore, wordScore)
            if (s >= 0.55f) n to s else null
        }.sortedByDescending { it.second }.map { it.first }
    }

    private fun bestWordScore(query: String, text: String): Float {
        val qw = query.split(' ')
        val tw = FuzzyMatcher.normalize(text).split(' ')
        var best = 0f
        for (a in qw) {
            for (b in tw) {
                val s = FuzzyMatcher.score(a, b)
                if (s > best) best = s
            }
        }
        return best * 0.9f
    }

    fun filterByDate(notes: List<Note>, from: Long, to: Long): List<Note> =
        notes.filter { it.createdAt in from..to }.sortedByDescending { it.createdAt }

    fun favorites(notes: List<Note>): List<Note> = notes.filter { it.favorite }.sortedByDescending { it.updatedAt }

    fun categories(notes: List<Note>): List<String> = notes.map { it.category }.distinct().sorted()

    /** Автоматическое название заметки: первые слова содержимого. */
    fun autoTitle(content: String): String {
        val clean = content.trim().replace(Regex("\\s+"), " ")
        if (clean.isEmpty()) return "Заметка"
        val words = clean.split(" ")
        val title = words.take(6).joinToString(" ")
        return if (words.size > 6) title + "…" else title
    }

    /** Вытаскивает дату из фразы («вчера», «сегодня», «позавчера»). */
    fun dateRangeFromPhrase(phrase: String, now: Long): Pair<Long, Long>? {
        val p = FuzzyMatcher.normalize(phrase)
        val day = 24 * 60 * 60 * 1000L
        val startOfToday = now - (now % day)
        return when {
            p.contains("вчера") -> (startOfToday - day) to (startOfToday - 1)
            p.contains("позавчера") -> (startOfToday - 2 * day) to (startOfToday - day - 1)
            p.contains("сегодня") -> startOfToday to (startOfToday + day - 1)
            else -> null
        }
    }
}
