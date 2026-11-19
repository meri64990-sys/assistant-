package com.nova.assistant.core

/**
 * Безопасный локальный вычислитель арифметических выражений.
 * Без использования eval и сторонних библиотек.
 */
object MathEvaluator {

    /** Извлекает выражение из фразы и считает его. */
    fun evaluatePhrase(text: String): Double? {
        val expr = extractExpression(text) ?: return null
        return evaluate(expr)
    }

    /** Выражение только если строка целиком является арифметикой. */
    fun evaluate(raw: String): Double? {
        val e = raw.replace(',', '.').replace(" ", "")
        if (e.isEmpty()) return null
        if (!e.matches(Regex("[0-9+\\-*/.^%()]+"))) return null
        return try {
            val v = Parser(e).parse()
            if (v.isFinite()) v else null
        } catch (e: Exception) {
            null
        }
    }

    private fun extractExpression(text: String): String? {
        val m = Regex("(-?\\d+(?:[.,]\\d+)?(?:\\s*[+\\-*/^%]\\s*-?\\d+(?:[.,]\\d+)?)+)").find(text)
            ?: return null
        return m.groupValues[1]
    }

    private class Parser(private val s: String) {
        private var i = 0

        fun parse(): Double {
            val v = expr()
            if (i != s.length) throw IllegalArgumentException("unexpected at $i")
            return v
        }

        private fun expr(): Double {
            var v = term()
            while (i < s.length && (s[i] == '+' || s[i] == '-')) {
                val op = s[i++]
                val r = term()
                v = if (op == '+') v + r else v - r
            }
            return v
        }

        private fun term(): Double {
            var v = factor()
            while (i < s.length && (s[i] == '*' || s[i] == '/' || s[i] == '%')) {
                val op = s[i++]
                val r = factor()
                v = when (op) {
                    '*' -> v * r
                    '/' -> v / r
                    else -> v % r
                }
            }
            return v
        }

        private fun factor(): Double {
            var v = unary()
            if (i < s.length && s[i] == '^') {
                i++
                v = Math.pow(v, factor())
            }
            return v
        }

        private fun unary(): Double {
            if (i < s.length && (s[i] == '-' || s[i] == '+')) {
                val op = s[i++]
                val v = unary()
                return if (op == '-') -v else v
            }
            return primary()
        }

        private fun primary(): Double {
            if (i < s.length && s[i] == '(') {
                i++
                val v = expr()
                if (i >= s.length || s[i] != ')') throw IllegalArgumentException("no )")
                i++
                return v
            }
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
            if (start == i) throw IllegalArgumentException("no number at $i")
            return s.substring(start, i).toDouble()
        }
    }

    fun formatNumber(v: Double): String {
        if (v == Math.floor(v) && Math.abs(v) < 1e15) return v.toLong().toString()
        return String.format("%.4f", v).trimEnd('0').trimEnd('.')
    }
}
