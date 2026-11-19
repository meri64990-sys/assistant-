package com.nova.assistant.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Локальная «база знаний» NOVA. Работает полностью на устройстве.
 * Если ответа нет — честно возвращает null, и помощник идёт в поиск
 * (или говорит, что не знает).
 */
interface LocalAIEngine {
    val name: String
    fun answer(question: String, ctx: ConversationContext): String?
}

class RuleBasedEngine(private val clock: () -> Long = { System.currentTimeMillis() }) : LocalAIEngine {

    override val name = "Локальный обработчик NOVA"

    override fun answer(question: String, ctx: ConversationContext): String? {
        val q = question.lowercase().trim()
        if (q.isBlank()) return null
        return arithmetic(question)
            ?: percent(question)
            ?: conversion(question)
            ?: dateTime(q)
            ?: knowledge(q)
            ?: capabilities(q)
    }

    // ---------------------------------------------------------------- //

    private fun arithmetic(text: String): String? {
        // Самая длинная числовая конструкция с операторами (скобки учитываются).
        val runs = Regex("[0-9+\\-*/^%().,\\s]+").findAll(text)
            .map { it.value }
            .filter { it.any { c -> c in "+-*/^%" } }
            .toList()
        val best = runs.maxByOrNull { it.length } ?: return null
        val v = MathEvaluator.evaluate(best) ?: return null
        return "Ответ: ${MathEvaluator.formatNumber(v)}"
    }

    private fun percent(text: String): String? {
        val m = Regex("(\\d+(?:[.,]\\d+)?)\\s*процент(?:а|ов)?\\s+от\\s+(\\d+(?:[.,]\\d+)?)", RegexOption.IGNORE_CASE)
            .find(text) ?: return null
        val p = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
        val base = m.groupValues[2].replace(',', '.').toDoubleOrNull() ?: return null
        return "${p}% от ${MathEvaluator.formatNumber(base)} — это ${MathEvaluator.formatNumber(base * p / 100.0)}"
    }

    private fun conversion(text: String): String? {
        val m = Regex(
            "(\\d+(?:[.,]\\d+)?)\\s*([а-яa-z/]+)\\s+(?:в|во|into)?\\s*([а-яa-z/]+)",
            RegexOption.IGNORE_CASE
        ).find(text) ?: return null
        val value = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
        val from = m.groupValues[2].lowercase()
        val to = m.groupValues[3].lowercase()
        val result = convert(value, from, to) ?: return null
        return "$value ${m.groupValues[2]} = ${MathEvaluator.formatNumber(result)} ${m.groupValues[3]}"
    }

    private fun convert(value: Double, from0: String, to0: String): Double? {
        val from = from0.lowercase()
        val to = to0.lowercase()

        val temp = mapOf(
            "ц" to "c", "цельсий" to "c", "цельсия" to "c", "цельсию" to "c", "c" to "c",
            "ф" to "f", "фаренгейт" to "f", "фаренгейта" to "f", "фаренгейты" to "f", "f" to "f",
            "к" to "k", "кельвин" to "k", "кельвина" to "k", "кельвины" to "k", "k" to "k"
        )
        val tf = lookup(temp, from)
        val tt = lookup(temp, to)
        if (tf != null && tt != null) {
            val c = when (tf) {
                "f" -> (value - 32.0) * 5.0 / 9.0
                "k" -> value - 273.15
                else -> value
            }
            return when (tt) {
                "f" -> c * 9.0 / 5.0 + 32.0
                "k" -> c + 273.15
                else -> c
            }
        }

        val length = mapOf(
            "мм" to 0.001, "см" to 0.01, "м" to 1.0, "метр" to 1.0, "км" to 1000.0,
            "дюйм" to 0.0254, "миля" to 1609.344, "фут" to 0.3048, "ярд" to 0.9144
        )
        val mass = mapOf(
            "мг" to 1e-6, "мкг" to 1e-9, "г" to 0.001, "грамм" to 0.001,
            "кг" to 1.0, "т" to 1000.0, "тонна" to 1000.0,
            "фунт" to 0.453592, "унция" to 0.0283495
        )
        val volume = mapOf("мл" to 0.001, "л" to 1.0, "литр" to 1.0, "галлон" to 3.785411784)
        val time = mapOf(
            "с" to 1.0, "сек" to 1.0, "секунда" to 1.0,
            "мин" to 60.0, "минута" to 60.0,
            "ч" to 3600.0, "час" to 3600.0,
            "день" to 86400.0, "сутки" to 86400.0, "неделя" to 604800.0
        )
        val speed = mapOf(
            "км/ч" to 0.2777778, "км/час" to 0.2777778,
            "м/с" to 1.0, "м/сек" to 1.0, "узел" to 0.514444
        )

        for (table in listOf(length, mass, volume, time, speed)) {
            val f = lookup(table, from) ?: continue
            val t = lookup(table, to) ?: continue
            return value * f / t
        }
        return null
    }

    /** Поиск единицы с учётом падежных окончаний: «метрах» → «метр». */
    private fun <T> lookup(table: Map<String, T>, word: String): T? {
        table[word]?.let { return it }
        val endings = listOf(
            "ов", "ам", "ах", "ях", "ье", "ия", "е", "ы", "и", "у", "ю", "а", "я", "ой", "ый", "ий"
        )
        for (e in endings) {
            if (word.endsWith(e) && word.length > e.length + 2) {
                table[word.dropLast(e.length)]?.let { return it }
            }
        }
        return null
    }

    private fun dateTime(q: String): String? {
        val now = Date(clock())
        return when {
            q.contains("который час") || q.contains("сколько времени") ||
                q.contains("текущее время") || q.contains("время сейчас") || q == "время" ->
                "Сейчас " + SimpleDateFormat("HH:mm", Locale("ru")).format(now)

            q.contains("какая сегодня дата") || q.contains("какое сегодня число") ||
                q.contains("какой сегодня день") || q.contains("сегодняшняя дата") ||
                q.contains("какой сегодня день недели") ->
                SimpleDateFormat("d MMMM yyyy, EEEE", Locale("ru")).format(now)

            q.contains("какой сейчас год") || q.contains("сколько сейчас лет") ->
                SimpleDateFormat("yyyy", Locale("ru")).format(now) + " год"

            else -> null
        }
    }

    private fun knowledge(q: String): String? = when {
        q.contains("столица россии") || q.contains("столица рф") -> "Столица России — Москва."
        q.contains("скорость света") -> "Скорость света в вакууме — около 299 792 458 м/с."
        q.contains("сколько дней в году") -> "В обычном году 365 дней, в високосном — 366."
        q.contains("самая высокая гора") -> "Самая высокая гора мира — Эверест, около 8849 метров."
        q.contains("самая длинная река") -> "Самая длинная река мира — Амазонка, около 6992 км."
        q.contains("сколько планет") -> "В Солнечной системе 8 планет."
        q.contains("что такое искусственный интеллект") || q.contains("что такое ии") ->
            "Искусственный интеллект — это программы, которые решают задачи, обычно требующие человеческого мышления: распознают речь, изображения, текст и учатся на данных."
        q.contains("как тебя зовут") || q.contains("кто ты") || q.contains("твое имя") ->
            "Меня зовут NOVA. Я работаю локально на вашем устройстве и не отправляю ваши данные на серверы."
        q.contains("спасибо") || q.contains("благодарю") -> "Всегда пожалуйста. Обращайтесь."
        else -> null
    }

    private fun capabilities(q: String): String? = when {
        q.contains("что ты умеешь") || q.contains("твои возможности") || q.contains("какие команды") ||
            q.contains("помоги мне") || q == "помощь" || q.contains("список команд") ->
            "Я умею: открывать приложения, читать экран, искать в интернете, создавать заметки, " +
                "запоминать сказанное, считать примеры, конвертировать единицы, помогать с заданиями и играми. " +
                "Скажите, например: «открой телеграм», «запиши в заметках купить зарядку», «посмотри на экран»."
        else -> null
    }
}
