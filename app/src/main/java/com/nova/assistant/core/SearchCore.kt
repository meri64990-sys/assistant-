package com.nova.assistant.core

/** Документ результата поиска. */
data class SearchDoc(
    val title: String,
    val url: String,
    val snippet: String
)

/**
 * Локальное извлечение и ранжирование информации из открытых веб-страниц.
 * Сеть используется только для загрузки страниц; обработка — на устройстве.
 */
object SearchCore {

    /** Извлекает читаемый текст из HTML. */
    fun extractText(html: String): String {
        var s = html
        s = Regex("(?is)<script[^>]*>.*?</script>").replace(s, " ")
        s = Regex("(?is)<style[^>]*>.*?</style>").replace(s, " ")
        s = Regex("(?is)<noscript[^>]*>.*?</noscript>").replace(s, " ")
        s = Regex("(?is)<!--.*?-->").replace(s, " ")
        s = Regex("(?i)<(br|/p|/div|/li|/h[1-6]|/tr)\\s*>").replace(s, "\n")
        s = Regex("<[^>]+>").replace(s, " ")
        s = decodeEntities(s)
        s = s.replace(Regex("[ \\t\\x0B\\f\\r]+"), " ")
        s = s.replace(Regex("\\n\\s*\\n+"), "\n")
        return s.trim()
    }

    fun decodeEntities(s: String): String {
        val named = mapOf(
            "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'",
            "nbsp" to " ", "mdash" to "—", "ndash" to "–", "laquo" to "«",
            "raquo" to "»", "hellip" to "…", "copy" to "©"
        )
        return s.replace(Regex("&(#x?[0-9a-fA-F]+|[a-zA-Z]+);")) { m ->
            val body = m.groupValues[1]
            when {
                body.startsWith("#x") || body.startsWith("#X") -> {
                    val code = body.substring(2).toIntOrNull(16) ?: return@replace m.value
                    code.toChar().toString()
                }
                body.startsWith("#") -> {
                    val code = body.substring(1).toIntOrNull() ?: return@replace m.value
                    code.toChar().toString()
                }
                else -> named[body.lowercase()] ?: m.value
            }
        }
    }

    /** Ранжирует документы по пересечению слов запроса. */
    fun rank(query: String, docs: List<SearchDoc>): List<SearchDoc> {
        val terms = FuzzyMatcher.normalize(query).split(' ').filter { it.length > 1 }
        if (terms.isEmpty()) return docs
        return docs.map { d ->
            val text = FuzzyMatcher.normalize(d.title + " " + d.snippet)
            var score = 0f
            for (t in terms) {
                if (text.contains(t)) score += 2f
                else if (FuzzyMatcher.containsFuzzy(text, t, 0.8f)) score += 1f
            }
            if (FuzzyMatcher.normalize(d.title).contains(FuzzyMatcher.normalize(query))) score += 3f
            d to score
        }.filter { it.second > 0f }
            .sortedByDescending { it.second }
            .map { it.first }
    }

    /** Короткий ответ: самые релевантные предложения из документов. */
    fun summarize(query: String, docs: List<SearchDoc>, maxSentences: Int = 3): String {
        val terms = FuzzyMatcher.normalize(query).split(' ').filter { it.length > 2 }
        val sentences = mutableListOf<Pair<String, Float>>()
        for (d in docs) {
            val text = if (d.snippet.isNotBlank()) d.snippet else d.title
            for (sent in text.split(Regex("(?<=[.!?])\\s+"))) {
                val s = sent.trim()
                if (s.length < 15) continue
                val ns = FuzzyMatcher.normalize(s)
                var score = 0f
                for (t in terms) if (ns.contains(t)) score += 1f
                if (score > 0f) sentences += s to score
            }
        }
        return sentences.sortedByDescending { it.second }
            .take(maxSentences)
            .joinToString(" ") { it.first }
    }

    /** Разбор HTML выдачи DuckDuckGo на список документов. */
    fun parseDuckDuckGo(html: String): List<SearchDoc> {
        val docs = mutableListOf<SearchDoc>()
        val linkRegex = Regex("(?is)<a[^>]+class=\"result__a\"[^>]+href=\"([^\"]+)\"[^>]*>(.*?)</a>")
        val snipRegex = Regex("(?is)<a[^>]+class=\"result__snippet\"[^>]*>(.*?)</a>")
        val links = linkRegex.findAll(html).toList()
        val snips = snipRegex.findAll(html).toList()
        for (i in links.indices) {
            val url = decodeRedirect(links[i].groupValues[1])
            val title = extractText(links[i].groupValues[2]).ifBlank { url }
            val snippet = if (i < snips.size) extractText(snips[i].groupValues[1]) else ""
            docs += SearchDoc(title, url, snippet)
            if (docs.size >= 8) break
        }
        return docs
    }

    /** DuckDuckGo прячет целевой URL в //duckduckgo.com/l/?uddg=... */
    fun decodeRedirect(url: String): String {
        val m = Regex("[?&]uddg=([^&]+)").find(url) ?: return url
        return runCatching { java.net.URLDecoder.decode(m.groupValues[1], "UTF-8") }.getOrDefault(url)
    }
}
