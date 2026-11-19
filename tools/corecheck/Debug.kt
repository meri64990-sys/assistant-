package corecheck

import com.nova.assistant.core.*

object Debug {
    @JvmStatic
    fun main(args: Array<String>) {
        val contacts = listOf("Саша", "Мама", "Иван")
        val parser = CommandParser(contacts = contacts)
        val phrases = listOf(
            "Нова, запиши в заметках: купить зарядку",
            "посмотри, что на экране",
            "открой настройки wifi",
            "запомни, что завтра мне нужно купить зарядку",
            "напомни мне позвонить маме через час",
            "открой телеграм, найди Сашу и напиши, что я скоро буду"
        )
        for (p in phrases) {
            val ctx = ConversationContext()
            val r = parser.parse(p, ctx)
            println("ФРАЗА: $p")
            println("  уточнение: ${r.clarification}")
            r.intents.forEach { i ->
                println("  ${i.type} app=${i.appName} contact=${i.contact} text=${i.text} note=${i.note} query=${i.query} conf=${i.confidence}")
            }
        }
    }
}
