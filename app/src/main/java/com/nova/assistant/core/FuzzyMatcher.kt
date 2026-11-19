package com.nova.assistant.core

/**
 * Нечёткое сопоставление строк для русской разговорной речи.
 *
 * Зачем: распознавание речи ошибается («тэлэглам» вместо «телеграм»),
 * пользователь говорит сокращения и сленг. Точное сравнение строк здесь
 * непригодно, поэтому используется расстояние Левенштейна с поправками
 * на типичные замены букв и на префиксы слов.
 */
object FuzzyMatcher {

    /** Буквы, которые распознавание речи часто путает между собой. */
    private val CHAR_EQUIV = mapOf(
        'ё' to 'е', 'э' to 'е', 'й' to 'и', 'ы' to 'и',
        'ъ' to 'ь', 'щ' to 'ш'
    )

    /** Упрощает строку до сравнимой формы. */
    fun normalize(s: String): String {
        val sb = StringBuilder(s.length)
        var i = 0
        val low = s.lowercase()
        while (i < low.length) {
            val two = if (i + 1 < low.length) low.substring(i, i + 2) else ""
            if (two == "тс") { sb.append('ц'); i += 2; continue }
            val c = low[i]
            if (c.isLetter() || c.isDigit()) {
                sb.append(CHAR_EQUIV[c] ?: c)
            } else if (c == ' ' || c == '\t' || c == '\n') {
                if (sb.isNotEmpty() && sb.last() != ' ') sb.append(' ')
            }
            i++
        }
        return sb.toString().trim()
    }

    /** Расстояние Левенштейна (две строки памяти, без рекурсии). */
    fun levenshtein(a: String, b: String, max: Int = Int.MAX_VALUE): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            var rowMin = cur[0]
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
                if (cur[j] < rowMin) rowMin = cur[j]
            }
            if (rowMin > max) return max + 1
            val tmp = prev; prev = cur; cur = tmp
        }
        return prev[b.length]
    }

    /** Сходство 0..1 для уже нормализованных строк. */
    fun similarity(a: String, b: String): Float {
        val na = normalize(a)
        val nb = normalize(b)
        if (na.isEmpty() && nb.isEmpty()) return 1f
        if (na.isEmpty() || nb.isEmpty()) return 0f
        if (na == nb) return 1f
        val maxLen = maxOf(na.length, nb.length)
        val d = levenshtein(na, nb, maxLen)
        return 1f - d.toFloat() / maxLen
    }

    /**
     * Сходство с учётом того, что одно слово может быть префиксом другого
     * («инста» → «instagram», «ютюб» → «ютуб»).
     */
    fun score(a: String, b: String): Float {
        val na = normalize(a)
        val nb = normalize(b)
        if (na.isEmpty() || nb.isEmpty()) return 0f
        if (na == nb) return 1f
        var s = similarity(na, nb)
        val minLen = minOf(na.length, nb.length)
        val maxLen = maxOf(na.length, nb.length)
        if (minLen >= 3 && (nb.startsWith(na) || na.startsWith(nb))) {
            s = maxOf(s, 0.82f + 0.18f * minLen / maxLen)
        }
        // Сопоставление по отдельным словам: «чат с сашей» vs «саша».
        val ta = na.split(' ')
        val tb = nb.split(' ')
        var bestToken = 0f
        for (x in ta) {
            for (y in tb) {
                val sc = similarity(x, y)
                if (sc > bestToken) bestToken = sc
            }
        }
        if (bestToken > s) s = bestToken * 0.97f
        return s.coerceIn(0f, 1f)
    }

    /** Лучший кандидат из списка с оценкой не ниже [threshold]. */
    fun bestMatch(query: String, candidates: Collection<String>, threshold: Float = 0.6f): Match? {
        var best: String? = null
        var bestScore = 0f
        for (c in candidates) {
            val s = score(query, c)
            if (s > bestScore) { bestScore = s; best = c }
        }
        return if (best != null && bestScore >= threshold) Match(best, bestScore) else null
    }

    /** Нечёткий поиск подстроки внутри строки. */
    fun containsFuzzy(haystack: String, needle: String, threshold: Float = 0.62f): Boolean {
        val nh = normalize(haystack)
        val nn = normalize(needle)
        if (nn.isEmpty()) return true
        if (nh.contains(nn)) return true
        if (nn.length < 3) return false
        val words = nh.split(' ')
        for (w in words) {
            if (score(w, nn) >= threshold) return true
        }
        // Скользящее окно по символам — для фраз, где нужно слово целиком.
        val step = 1
        var i = 0
        val win = nn.length
        while (i + win <= nh.length) {
            if (score(nh.substring(i, i + win), nn) >= threshold + 0.08f) return true
            i += step
        }
        return false
    }

    /**
     * Возвращает индекс в ИСХОДНОЙ строке, до которого нормализованная длина
     * достигает [normalizedLength]. Нужно, чтобы отрезать префикс-команду и
     * сохранить оригинальный текст (с ё, заглавными, пунктуацией) для заметок.
     */
    fun prefixEnd(original: String, normalizedLength: Int): Int {
        if (normalizedLength <= 0) return 0
        var lo = 0
        var hi = original.length
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (normalize(original.substring(0, mid)).length >= normalizedLength) hi = mid else lo = mid + 1
        }
        return lo
    }

    /** Транслитерация латиницы в кириллицу («telegram» → «телеграм»). */
    fun toCyrillic(s: String): String {
        val map = mapOf(
            "yo" to "ё", "zh" to "ж", "kh" to "х", "ts" to "ц", "ch" to "ч",
            "sh" to "ш", "shch" to "щ", "yu" to "ю", "ya" to "я",
            "a" to "а", "b" to "б", "v" to "в", "g" to "г", "d" to "д",
            "e" to "е", "z" to "з", "i" to "и", "j" to "й", "k" to "к",
            "l" to "л", "m" to "м", "n" to "н", "o" to "о", "p" to "п",
            "r" to "р", "s" to "с", "t" to "т", "u" to "у", "f" to "ф",
            "h" to "х", "c" to "ц", "y" to "ы", "w" to "в", "x" to "кс",
            "q" to "к", "g" to "г"
        )
        val sb = StringBuilder()
        var i = 0
        val low = s.lowercase()
        while (i < low.length) {
            var matched = false
            for (len in 4 downTo 1) {
                if (i + len <= low.length) {
                    val sub = low.substring(i, i + len)
                    val tr = map[sub]
                    if (tr != null) { sb.append(tr); i += len; matched = true; break }
                }
            }
            if (!matched) sb.append(low[i])
            i++
        }
        return sb.toString()
    }

    /** Сравнивает с учётом транслитерации: «instagram» и «инстаграм» — близки. */
    fun scoreTranslit(a: String, b: String): Float {
        val s1 = score(a, b)
        val s2 = score(toCyrillic(a), b)
        val s3 = score(a, toCyrillic(b))
        return maxOf(s1, s2, s3)
    }
}

data class Match(val value: String, val score: Float)
