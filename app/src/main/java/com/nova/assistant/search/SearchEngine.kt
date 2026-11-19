package com.nova.assistant.search

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.nova.assistant.core.SearchCore
import com.nova.assistant.core.SearchDoc
import java.net.HttpURLConnection
import java.net.URL

/**
 * Поиск информации без платного API.
 *
 * Два реальных пути:
 *  1. Открыть поисковую систему в браузере (всегда работает, если есть браузер).
 *  2. Загрузить выдачу DuckDuckGo HTML и извлечь текст локально
 *     (нужет интернет; при его отсутствии честно возвращает null).
 */
class SearchEngine(private val context: Context) {

    /** Открывает поиск в браузере. */
    fun openSearch(query: String): Boolean {
        val url = "https://duckduckgo.com/?q=" + Uri.encode(query)
        return try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            true
        } catch (e: Exception) {
            false
        }
    }

    fun openUrl(url: String): Boolean = try {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        true
    } catch (e: Exception) {
        false
    }

    /**
     * Ищет и возвращает краткий ответ. Выполняется в фоновом потоке.
     * Возвращает null, если сеть недоступна или ничего не найдено.
     */
    fun search(query: String, cb: (String?) -> Unit) {
        Thread {
            val docs = fetchResults(query)
            if (docs == null || docs.isEmpty()) {
                cb(null)
                return@Thread
            }
            val ranked = SearchCore.rank(query, docs)
            val summary = SearchCore.summarize(query, ranked)
            val answer = if (summary.isNotBlank()) {
                summary
            } else {
                ranked.firstOrNull()?.snippet ?: ranked.firstOrNull()?.title
            }
            cb(answer)
        }.start()
    }

    fun fetchResults(query: String): List<SearchDoc>? {
        val url = "https://duckduckgo.com/html/?q=" + Uri.encode(query)
        val html = fetch(url) ?: return null
        val docs = SearchCore.parseDuckDuckGo(html)
        return docs.ifEmpty { null }
    }

    /** Загружает страницу и извлекает читаемый текст. */
    fun fetchPageText(url: String, cb: (String?) -> Unit) {
        Thread {
            val html = fetch(url)
            cb(html?.let { SearchCore.extractText(it) })
        }.start()
    }

    private fun fetch(url: String): String? = try {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 8000
        conn.requestProperties["User-Agent"] =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36"
        conn.instanceFollowRedirects = true
        if (conn.responseCode !in 200..299) {
            conn.disconnect()
            return null
        }
        val text = conn.inputStream.bufferedReader().use { it.readText() }
        conn.disconnect()
        text
    } catch (e: Exception) {
        null
    }
}
