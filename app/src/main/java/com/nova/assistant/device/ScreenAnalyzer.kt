package com.nova.assistant.device

import android.content.Context
import android.content.pm.PackageManager
import com.nova.assistant.core.FuzzyMatcher

/**
 * Анализ видимого экрана. Работает на реальных данных службы
 * спец. возможностей: текст, кнопки, поля, прокрутка.
 *
 * Если служба не включена — честно сообщает об этом, а не выдумывает ответ.
 */
object ScreenAnalyzer {

    /** Читает экран и возвращает снимок. */
    fun read(context: Context, cb: (AccessibilityController.ScreenSnapshot?) -> Unit) {
        if (!AccessibilityController.isReady()) { cb(null); return }
        AccessibilityController.snapshot { cb(it) }
    }

    /** Короткое описание экрана для пользователя. */
    fun describe(context: Context, snap: AccessibilityController.ScreenSnapshot?): String {
        if (snap == null) {
            return "Не могу прочитать экран: служба специальных возможностей не включена. " +
                "Включите её в настройках, и я увижу текст и кнопки."
        }
        val app = appLabel(context, snap.packageName)
        val texts = snap.elements.map { it.text }.filter { it.isNotBlank() }.distinct()
        val buttons = snap.buttons.filter { it.text.isNotBlank() }.map { it.text }.distinct().take(6)
        return buildString {
            append("Открыто: ").append(app).append(". ")
            if (buttons.isNotEmpty()) {
                append("Кнопки: ").append(buttons.joinToString(", ")).append(". ")
            }
            if (texts.isNotEmpty()) {
                append("Текст: ").append(texts.take(8).joinToString(". "))
            } else {
                append("Текст на экране не найден.")
            }
        }
    }

    /** Ищет ошибку на экране. */
    fun findError(snap: AccessibilityController.ScreenSnapshot?): String? {
        if (snap == null) return null
        val markers = listOf("ошибка", "error", "не удалось", "недоступно", "не найдено", "сбой", "подключение отсутствует", "неверный")
        val all = snap.elements.map { it.text }
        for (t in all) {
            val n = FuzzyMatcher.normalize(t)
            if (markers.any { n.contains(it) }) return t
        }
        return null
    }

    /** Находит элемент по названию (для «найди кнопку продолжения»). */
    fun find(snap: AccessibilityController.ScreenSnapshot?, query: String): AccessibilityController.ScreenElement? {
        if (snap == null || query.isBlank()) return null
        val q = FuzzyMatcher.normalize(query)
        var best: AccessibilityController.ScreenElement? = null
        var bestScore = 0f
        for (e in snap.elements) {
            val candidates = listOf(e.text, e.desc).filter { it.isNotBlank() }
            for (c in candidates) {
                val s = FuzzyMatcher.score(q, FuzzyMatcher.normalize(c))
                if (s > bestScore) { bestScore = s; best = e }
            }
        }
        return if (bestScore >= 0.6f) best else null
    }

    /** Прокручивает экран в поисках элемента (ограниченное число попыток). */
    fun scrollTo(
        context: Context,
        query: String,
        maxSwipes: Int = 4,
        cb: (AccessibilityController.ScreenElement?) -> Unit
    ) {
        if (!AccessibilityController.isReady()) { cb(null); return }
        attemptScroll(context, query, 0, maxSwipes, cb)
    }

    private fun attemptScroll(
        context: Context,
        query: String,
        attempt: Int,
        max: Int,
        cb: (AccessibilityController.ScreenElement?) -> Unit
    ) {
        AccessibilityController.snapshot { snap ->
            val found = find(snap, query)
            if (found != null) { cb(found); return@snapshot }
            if (attempt >= max) { cb(null); return@snapshot }
            AccessibilityController.swipe("up") { ok ->
                if (!ok) { cb(null); return@swipe }
                attemptScroll(context, query, attempt + 1, max, cb)
            }
        }
    }

    /**
     * Определяет, похоже ли, что на экране игра. Эвристика по пакету и тексту.
     * Не претендует на точность — используется только для подсказок.
     */
    fun looksLikeGame(context: Context, snap: AccessibilityController.ScreenSnapshot?): Boolean {
        if (snap == null) return false
        val pkg = snap.packageName ?: return false
        val known = listOf(
            "com.supercell", "com.king", "com.rovio", "com.mojang", "com.epicgames",
            "com.tencent", "com.activision", "com.gameloft", "com.nianticlabs"
        )
        if (known.any { pkg.startsWith(it) }) return true
        val text = snap.text.lowercase()
        val markers = listOf("уровень", "игра", "hp", "жизн", "монет", "очков", "wave", "level", "score", "играть")
        return markers.count { text.contains(it) } >= 2
    }

    /** Название приложения по пакету. */
    fun appLabel(context: Context, pkg: String?): String {
        if (pkg == null) return "неизвестное приложение"
        return try {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (e: Exception) {
            pkg
        }
    }
}
