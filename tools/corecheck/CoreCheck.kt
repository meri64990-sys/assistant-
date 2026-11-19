package corecheck

import com.nova.assistant.core.*

/**
 * Проверка реально работающего ядра NOVA (без Android).
 * Запуск: tools/corecheck/run.sh
 */
object CoreCheck {

    private var passed = 0
    private var failed = 0
    private val failures = mutableListOf<String>()

    private fun check(name: String, cond: Boolean, detail: String = "") {
        if (cond) {
            passed++
            println("  OK   $name")
        } else {
            failed++
            failures += "$name $detail"
            println("  FAIL $name $detail")
        }
    }

    private fun <T> eq(name: String, expected: T, actual: T) =
        check(name, expected == actual, "(ожидалось: $expected, получено: $actual)")

    @JvmStatic
    fun main(args: Array<String>) {
        println("=== NOVA core check ===")

        val contacts = listOf("Саша", "Мама", "Иван")
        val parser = CommandParser(contacts = contacts)
        val ctx = ConversationContext()

        println("\n-- распознавание команд --")
        run {
            val r = parser.parse("открой тэлэглам", ctx)
            eq("тэлэглам -> OPEN_APP", IntentType.OPEN_APP, r.intents.firstOrNull()?.type)
            eq("тэлэглам -> telegram", "telegram", r.intents.firstOrNull()?.appName)
        }
        run {
            val r = parser.parse("открой ютуб", ctx)
            eq("ютуб -> youtube", "youtube", r.intents.firstOrNull()?.appName)
        }
        run {
            val r = parser.parse("зайди в инсту", ctx)
            eq("инста -> instagram", "instagram", r.intents.firstOrNull()?.appName)
        }
        run {
            val r = parser.parse("открой ютюб", ctx)
            eq("ютюб -> youtube", "youtube", r.intents.firstOrNull()?.appName)
        }
        run {
            val r = parser.parse("запусти калькулятор", ctx)
            eq("калькулятор", "calculator", r.intents.firstOrNull()?.appName)
        }
        run {
            val r = parser.parse("напиши в заметках привет", ctx)
            eq("заметка тип", IntentType.CREATE_NOTE, r.intents.firstOrNull()?.type)
            eq("заметка текст", "привет", r.intents.firstOrNull()?.note)
        }
        run {
            val r = parser.parse("Нова, запиши в заметках: купить зарядку", ctx)
            eq("будильник-слово отброшено", IntentType.CREATE_NOTE, r.intents.firstOrNull()?.type)
            eq("текст заметки", "купить зарядку", r.intents.firstOrNull()?.note)
        }
        run {
            val r = parser.parse("посмотри, что на экране", ctx)
            eq("чтение экрана", IntentType.READ_SCREEN, r.intents.firstOrNull()?.type)
        }
        run {
            val r = parser.parse("прочитай сообщение", ctx)
            eq("прочитай сообщение", IntentType.READ_SCREEN, r.intents.firstOrNull()?.type)
        }
        run {
            val r = parser.parse("что мне делать", ctx)
            eq("что делать", IntentType.DESCRIBE_SCREEN, r.intents.firstOrNull()?.type)
        }
        run {
            val r = parser.parse("запомни, что завтра мне нужно купить зарядку", ctx)
            eq("запомни тип", IntentType.MEMORIZE, r.intents.firstOrNull()?.type)
            check("запомни текст", r.intents.firstOrNull()?.text?.contains("завтра") == true,
                "(получено: ${r.intents.firstOrNull()?.text})")
        }
        run {
            val r = parser.parse("стоп", ctx)
            eq("стоп", IntentType.STOP, r.intents.firstOrNull()?.type)
        }
        run {
            val r = parser.parse("найди в гугле почему греется телефон", ctx)
            eq("поиск тип", IntentType.SEARCH_WEB, r.intents.firstOrNull()?.type)
            eq("поиск запрос", "почему греется телефон", r.intents.firstOrNull()?.query)
        }
        run {
            val c = ConversationContext(screenText = "ошибка: недостаточно памяти")
            val r = parser.parse("найди это в гугле", c)
            eq("поиск с контекстом", "ошибка: недостаточно памяти", r.intents.firstOrNull()?.query)
        }

        println("\n-- составные команды --")
        run {
            val r = parser.parse("открой телеграм, найди Сашу и напиши, что я скоро буду", ctx)
            val types = r.intents.map { it.type }
            check("3 намерения", types.size == 3, "(получено: $types)")
            eq("1 -> OPEN_APP", IntentType.OPEN_APP, r.intents.getOrNull(0)?.type)
            eq("2 -> OPEN_CHAT", IntentType.OPEN_CHAT, r.intents.getOrNull(1)?.type)
            eq("контакт Саша", "Саша", r.intents.getOrNull(1)?.contact)
            eq("3 -> SEND_MESSAGE", IntentType.SEND_MESSAGE, r.intents.getOrNull(2)?.type)
            eq("контакт в сообщении", "Саша", r.intents.getOrNull(2)?.contact)
            eq("текст сообщения", "я скоро буду", r.intents.getOrNull(2)?.text)
        }
        run {
            val r = parser.parse("позвони маме", ctx)
            eq("звонок тип", IntentType.CALL_CONTACT, r.intents.firstOrNull()?.type)
            eq("звонок контакт", "Мама", r.intents.firstOrNull()?.contact)
        }
        run {
            val r = parser.parse("помоги пройти этот уровень", ctx)
            eq("игра", IntentType.GAME_HELP, r.intents.firstOrNull()?.type)
        }
        run {
            val r = parser.parse("разберись с заданием по истории", ctx)
            eq("учёба", IntentType.EDUCATION_HELP, r.intents.firstOrNull()?.type)
        }
        run {
            val r = parser.parse("поставь таймер на 5 минут", ctx)
            eq("таймер", IntentType.TIMER, r.intents.firstOrNull()?.type)
        }
        run {
            val r = parser.parse("напомни мне позвонить маме через час", ctx)
            eq("напоминание", IntentType.REMINDER, r.intents.firstOrNull()?.type)
        }
        run {
            val r = parser.parse("покажи заметки", ctx)
            eq("список заметок", IntentType.LIST_NOTES, r.intents.firstOrNull()?.type)
        }
        run {
            val r = parser.parse("открой настройки wifi", ctx)
            eq("настройки раздела", IntentType.OPEN_SUB_SETTINGS, r.intents.firstOrNull()?.type)
        }

        println("\n-- планировщик --")
        run {
            val goal = "Найди в МЭШ задание по истории на следующий урок, открой тест, изучи вопросы и помоги мне решить его"
            val intents = parser.parse(goal, ctx).intents
            val plan = TaskPlanner.plan(goal, intents, ctx, now = 0L)
            eq("домен учёбы", TaskPlanner.DOMAIN_EDUCATION, plan.domain)
            check("шагов >= 12", plan.steps.size >= 12, "(получено ${plan.steps.size})")
            val encoded = TaskPlanCodec.encode(plan)
            val decoded = TaskPlanCodec.decode(encoded)
            check("кодек плана round-trip", decoded?.steps?.size == plan.steps.size)
            check("кодек плана цель", decoded?.goal == plan.goal)
        }
        run {
            val goal = "открой телеграм, найди Сашу и напиши, что я скоро буду"
            val intents = parser.parse(goal, ctx).intents
            val plan = TaskPlanner.plan(goal, intents, ctx, now = 0L)
            eq("домен мессенджера", TaskPlanner.DOMAIN_MESSENGER, plan.domain)
            check("есть CONFIRM", plan.steps.any { it.kind == StepKind.CONFIRM })
            check("есть FIND_ELEMENT", plan.steps.any { it.kind == StepKind.FIND_ELEMENT })
        }

        println("\n-- безопасность --")
        run {
            eq("OPEN_APP безопасно", Risk.SAFE, SafetyManager.classify(Intent(IntentType.OPEN_APP, "открой телеграм", appName = "telegram")).risk)
            eq("SEND требует подтверждения", Risk.CONFIRM, SafetyManager.classify(Intent(IntentType.SEND_MESSAGE, "напиши саше", contact = "Саша", text = "привет")).risk)
            eq("CALL требует подтверждения", Risk.CONFIRM, SafetyManager.classify(Intent(IntentType.CALL_CONTACT, "позвони маме", contact = "Мама")).risk)
            eq("оплата запрещена", Risk.FORBIDDEN, SafetyManager.classify(Intent(IntentType.UNKNOWN, "оплати счет")).risk)
            eq("удаление запрещено", Risk.FORBIDDEN, SafetyManager.classify(Intent(IntentType.UNKNOWN, "удали все данные")).risk)
        }

        println("\n-- локальные знания --")
        run {
            val engine = RuleBasedEngine(clock = { 0L })
            eq("2+2*3", "Ответ: 8", engine.answer("сколько будет 2+2*3", ConversationContext()))
            eq("(2+3)*4", "Ответ: 20", engine.answer("(2+3)*4", ConversationContext()))
            check("который час", engine.answer("который час", ConversationContext())?.contains(":") == true)
            check("5 км в метрах", engine.answer("сколько в 5 км метрах", ConversationContext())?.contains("5000") == true,
                "(получено: ${engine.answer("сколько в 5 км метрах", ConversationContext())})")
            check("30 цельсий в фаренгейты", engine.answer("30 цельсий в фаренгейты", ConversationContext())?.contains("86") == true,
                "(получено: ${engine.answer("30 цельсий в фаренгейты", ConversationContext())})")
            check("15 процентов от 200", engine.answer("15 процентов от 200", ConversationContext())?.contains("30") == true,
                "(получено: ${engine.answer("15 процентов от 200", ConversationContext())})")
            check("столица россии", engine.answer("какая столица России", ConversationContext())?.contains("Москва") == true)
            check("что ты умеешь", engine.answer("что ты умеешь", ConversationContext()) != null)
            check("неизвестное -> null", engine.answer("бронебойный самолёт пятого поколения", ConversationContext()) == null)
        }

        println("\n-- заметки --")
        run {
            val notes = listOf(
                Note(1, "Купить зарядку", "купить зарядку для телефона", "Заметки", false, 1000L),
                Note(2, "Расписание", "история в понедельник", "Учёба", true, 2000L),
                Note(3, "Пароль", "важное", "Личное", false, 3000L)
            )
            val found = NotesQuery.search(notes, "зарядку")
            check("поиск заметки", found.isNotEmpty() && found.first().id == 1L, "(получено: ${found.map { it.id }})")
            eq("авто-заголовок", "купить зарядку для телефона", NotesQuery.autoTitle("купить зарядку для телефона"))
            eq("авто-заголовок с обрезкой", "раз два три четыре пять шесть…", NotesQuery.autoTitle("раз два три четыре пять шесть семь"))
        }

        println("\n-- поиск --")
        run {
            val html = "<html><head><title>T</title></head><body><script>var x=1;</script><h1>Привет <b>мир</b></h1><p>Это первое предложение про телефон. Второе предложение.</p></body></html>"
            val text = SearchCore.extractText(html)
            check("извлечение текста", text.contains("Привет мир") && !text.contains("var x"), "(получено: $text)")
            val ddg = """<a class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2Fpage">Пример</a>
                <a class="result__snippet">Про телефон и его аккумулятор</a>"""
            val docs = SearchCore.parseDuckDuckGo(ddg)
            check("парсинг DDG", docs.isNotEmpty() && docs[0].url == "https://example.com/page", "(получено: $docs)")
            val ranked = SearchCore.rank("телефон", listOf(
                SearchDoc("Прочее", "u1", "про кошек"),
                SearchDoc("Телефон", "u2", "про телефон и аккумулятор")
            ))
            check("ранжирование", ranked.isNotEmpty() && ranked.first().title == "Телефон", "(получено: ${ranked.map { it.title }})")
        }

        println("\n=== итог: $passed успешно, $failed с ошибкой ===")
        if (failures.isNotEmpty()) {
            failures.forEach { println(" - $it") }
            kotlin.system.exitProcess(1)
        }
    }
}
