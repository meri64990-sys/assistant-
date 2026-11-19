package com.nova.assistant.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.nova.assistant.NovaApp
import com.nova.assistant.core.CommandParser
import com.nova.assistant.core.ConversationContext
import com.nova.assistant.core.FuzzyMatcher
import com.nova.assistant.core.Intent
import com.nova.assistant.core.IntentResolver
import com.nova.assistant.core.IntentType
import com.nova.assistant.core.NotesQuery
import com.nova.assistant.core.SafetyManager
import com.nova.assistant.device.AccessibilityController
import com.nova.assistant.device.AppLauncher
import com.nova.assistant.device.ScreenAnalyzer
import com.nova.assistant.education.EducationAssistant
import com.nova.assistant.game.GameAssistant
import com.nova.assistant.overlay.CharacterState
import com.nova.assistant.permission.PermissionManager
import com.nova.assistant.search.SearchEngine
import com.nova.assistant.tasks.TaskAgent
import com.nova.assistant.tasks.TaskExecutor
import com.nova.assistant.voice.VoiceEngine
import kotlinx.coroutines.runBlocking

/**
 * Ядро помощника: связывает голос, разбор команд, память, планировщик,
 * исполнитель и устройство. Работает внутри фонового сервиса.
 */
class AssistantCore(
    private val context: Context,
    private val host: Host
) {

    interface Host {
        fun showMessage(text: String)
        fun setState(state: CharacterState)
        fun speak(text: String)
        fun askConfirm(question: String, onYes: () -> Unit, onNo: () -> Unit)
        fun showProgress(text: String)
        fun onTaskFinished(report: String)
        fun setLevel(level: Float) {}
    }

    private val main = Handler(Looper.getMainLooper())
    private val settings = NovaApp.settings!!
    private val notes = NovaApp.notes!!
    private val history = NovaApp.history!!
    private val memory = NovaApp.memory!!
    private val localAi = NovaApp.localAi!!
    private val conversation: ConversationContext = NovaApp.conversation ?: ConversationContext().also { NovaApp.conversation = it }

    private val launcher = AppLauncher(context)
    private val search = SearchEngine(context)
    private val executor = TaskExecutor(context, launcher, notes, search, memory)
    private val agent = TaskAgent(executor, history)

    private var parser = CommandParser()
    private var resolver = IntentResolver(parser)
    private var pending: Intent? = null

    private lateinit var voice: VoiceEngine

    init {
        voice = createVoice()
    }

    private fun createVoice(): VoiceEngine = VoiceEngine(context, settings, object : VoiceEngine.Callbacks {
        override fun onPartial(text: String) {}
        override fun onResult(text: String) = onVoiceResult(text)
        override fun onWakeWord() {
            host.setState(CharacterState.LISTENING)
            host.speak("Да?")
        }
        override fun onUserSpeaking() {
            if (voice.isSpeaking()) voice.stopSpeaking()
        }
        override fun onError(message: String) {
            host.showMessage(message)
        }
        override fun onRms(level: Float) {
            host.setLevel((level / 20f).coerceIn(0f, 1f))
        }
        override fun onListeningChanged(listening: Boolean) {
            if (!listening && settings.alwaysListening) host.setState(CharacterState.IDLE)
        }
    })

    val voiceEngine: VoiceEngine get() = voice

    // ------------------------------------------------------------------ //
    // Жизненный цикл
    // ------------------------------------------------------------------ //

    fun start() {
        refreshEnvironment()
        if (settings.alwaysListening && !settings.isEco) {
            if (PermissionManager.hasMic(context)) {
                voice.listenContinuously()
                host.setState(CharacterState.LISTENING)
            } else {
                host.setState(CharacterState.IDLE)
                host.showMessage("Нет разрешения на микрофон — голосовые команды недоступны")
            }
        } else {
            host.setState(CharacterState.IDLE)
        }
    }

    fun destroy() {
        voice.destroy()
    }

    /** Обновляет контекст устройства: контакты и установленные приложения. */
    fun refreshEnvironment() {
        Thread {
            val contacts = PermissionManager.loadContacts(context)
            val apps = PermissionManager.loadInstalledApps(context).map { it.label }
            main.post {
                parser = CommandParser(contacts, apps)
                resolver = IntentResolver(parser)
            }
        }.start()
    }

    fun listen() {
        voice.listenOnce()
        host.setState(CharacterState.LISTENING)
    }

    fun onAccessibilityEvent(pkg: String?, text: String?) {
        conversation.currentPackage = pkg
        if (!text.isNullOrBlank()) conversation.screenText = text
    }

    // ------------------------------------------------------------------ //
    // Обработка команд
    // ------------------------------------------------------------------ //

    fun onVoiceResult(text: String) {
        conversation.touch()
        host.setState(CharacterState.THINKING)
        host.showMessage("«$text»")

        val result = resolver.resolve(text, conversation)
        if (result.intents.isEmpty()) {
            val q = result.clarification ?: "Не поняла команду."
            host.setState(CharacterState.IDLE)
            host.speak(q)
            host.showMessage(q)
            return
        }

        // Команды «стоп/отмена» обрабатываются сразу.
        val first = result.intents.first()
        if (first.type == IntentType.STOP) { stopAll(); return }
        if (first.type == IntentType.CONFIRM) { handleConfirm(true); return }
        if (first.type == IntentType.DENY) { handleConfirm(false); return }

        val needsAgent = result.intents.size > 1 || result.intents.any {
            it.type == IntentType.GAME_HELP || it.type == IntentType.EDUCATION_HELP
        }
        if (needsAgent) {
            runAgent(text, result.intents)
        } else {
            for (intent in result.intents) handleSimple(intent)
        }
    }

    private fun handleConfirm(yes: Boolean) {
        val p = pending
        pending = null
        if (p == null) {
            host.setState(CharacterState.IDLE)
            host.speak("Нет действия, которое нужно подтвердить")
            return
        }
        if (yes) {
            host.setState(CharacterState.ACTING)
            executeAllowed(p)
        } else {
            host.setState(CharacterState.IDLE)
            host.speak("Отменила")
        }
    }

    private fun handleSimple(intent: Intent) {
        val decision = SafetyManager.classify(intent)
        if (decision.risk == com.nova.assistant.core.Risk.FORBIDDEN) {
            host.setState(CharacterState.ERROR)
            host.speak(decision.reason)
            host.showMessage(decision.reason)
            return
        }
        if (decision.risk == com.nova.assistant.core.Risk.CONFIRM && settings.confirmSensitive) {
            pending = intent
            host.setState(CharacterState.CONFIRM)
            val q = SafetyManager.confirmationQuestion(intent)
            host.askConfirm(q, { executeAllowed(intent) }, {
                pending = null
                host.setState(CharacterState.IDLE)
                host.speak("Отменила")
            })
            return
        }
        executeAllowed(intent)
    }

    private fun executeAllowed(intent: Intent) {
        when (intent.type) {
            IntentType.OPEN_APP -> {
                host.setState(CharacterState.ACTING)
                val app = intent.appName
                val launched = app?.let { launcher.launchByName(it) }
                if (launched != null) {
                    conversation.lastAppName = launched.label
                    conversation.lastAppPackage = launched.packageName
                    host.setState(CharacterState.DONE)
                    host.speak("Открываю ${launched.label}")
                } else {
                    host.setState(CharacterState.ERROR)
                    host.speak("Не нашла приложение «$app»")
                }
            }

            IntentType.OPEN_SETTINGS -> {
                host.setState(CharacterState.ACTING)
                launcher.openSettings(null)
                host.setState(CharacterState.DONE)
                host.speak("Открываю настройки")
            }

            IntentType.OPEN_SUB_SETTINGS -> {
                host.setState(CharacterState.ACTING)
                launcher.openSettings(intent.query)
                host.setState(CharacterState.DONE)
                host.speak("Открываю настройки: ${intent.query}")
            }

            IntentType.GO_HOME -> {
                launcher.home()
                host.setState(CharacterState.IDLE)
            }

            IntentType.GO_BACK -> AccessibilityController.back { host.setState(CharacterState.IDLE) }

            IntentType.SEARCH_WEB -> {
                host.setState(CharacterState.SEARCHING)
                val q = intent.query ?: ""
                search.search(q) { answer ->
                    main.post {
                        if (answer != null) {
                            host.setState(CharacterState.DONE)
                            host.speak(answer)
                            host.showMessage(answer)
                        } else {
                            // Нет сети или ничего не найдено — открываем браузер.
                            search.openSearch(q)
                            host.setState(CharacterState.DONE)
                            host.speak("Не удалось получить ответ автоматически. Открываю поиск.")
                        }
                    }
                }
            }

            IntentType.READ_SCREEN -> readScreen(describe = false)
            IntentType.DESCRIBE_SCREEN -> readScreen(describe = true)

            IntentType.CREATE_NOTE -> {
                host.setState(CharacterState.ACTING)
                val content = intent.note ?: ""
                Thread {
                    val note = runBlocking { notes.create(content) }
                    main.post {
                        host.setState(CharacterState.DONE)
                        host.speak("Записала: ${note.title}")
                        host.showMessage("Заметка: ${note.title}")
                    }
                }.start()
            }

            IntentType.LIST_NOTES -> {
                Thread {
                    val list = runBlocking { notes.all() }
                    main.post {
                        val text = if (list.isEmpty()) "Заметок пока нет"
                        else list.take(5).joinToString(". ") { it.title }
                        host.setState(CharacterState.DONE)
                        host.speak("Ваши заметки: $text")
                    }
                }.start()
            }

            IntentType.FIND_NOTE -> {
                Thread {
                    val q = intent.query
                    val list = runBlocking {
                        if (q.isNullOrBlank()) notes.all() else notes.search(q)
                    }
                    val byDate = if (q != null) runBlocking { notes.findByDate(q) } else emptyList()
                    val result = (list + byDate).distinctBy { it.id }.take(5)
                    main.post {
                        val text = if (result.isEmpty()) "Ничего не нашла"
                        else result.joinToString(". ") { it.title + ": " + it.content }
                        host.setState(CharacterState.DONE)
                        host.speak(text)
                    }
                }.start()
            }

            IntentType.MEMORIZE -> {
                val v = intent.text ?: ""
                Thread {
                    runBlocking { memory.remember("fact_" + System.currentTimeMillis(), v, "fact") }
                    main.post {
                        conversation.lastDictated = v
                        host.setState(CharacterState.DONE)
                        host.speak("Запомнила")
                    }
                }.start()
            }

            IntentType.RECALL -> {
                Thread {
                    val q = intent.query
                    val mem = if (q.isNullOrBlank()) runBlocking { memory.byKind("fact") } else runBlocking { memory.search(q) }
                    main.post {
                        val text = if (mem.isEmpty()) "Пока ничего не запоминала"
                        else mem.take(5).joinToString(". ") { it.value }
                        host.setState(CharacterState.DONE)
                        host.speak(text)
                    }
                }.start()
            }

            IntentType.FORGET -> {
                Thread {
                    runBlocking { memory.clear() }
                    main.post {
                        host.setState(CharacterState.DONE)
                        host.speak("Память очищена")
                    }
                }.start()
            }

            IntentType.TIMER -> setTimer(intent.text)
            IntentType.REMINDER -> setReminder(intent.text)

            IntentType.CALL_CONTACT -> {
                host.setState(CharacterState.ACTING)
                val phone = findPhone(intent.contact)
                if (phone != null) {
                    launcher.launch(
                        android.content.Intent(
                            android.content.Intent.ACTION_DIAL,
                            Uri.parse("tel:" + Uri.encode(phone))
                        )
                    )
                    host.setState(CharacterState.DONE)
                    host.speak("Набираю ${intent.contact}")
                } else {
                    host.setState(CharacterState.ERROR)
                    host.speak("Не нашла номер для ${intent.contact}")
                }
            }

            IntentType.OPEN_CHAT -> runAgent(intent.raw, listOf(intent))
            IntentType.SEND_MESSAGE -> runAgent(intent.raw, listOf(intent))

            IntentType.GAME_HELP, IntentType.EDUCATION_HELP -> runAgent(intent.raw, listOf(intent))

            IntentType.OPEN_URL -> {
                host.setState(CharacterState.ACTING)
                search.openUrl(intent.query ?: "")
                host.setState(CharacterState.DONE)
            }

            IntentType.UNKNOWN -> {
                // Локальная база знаний как запасной вариант.
                val answer = localAi.answer(intent.raw, conversation)
                if (answer != null) {
                    host.setState(CharacterState.DONE)
                    host.speak(answer)
                } else {
                    host.setState(CharacterState.IDLE)
                    host.speak(intent.clarification ?: "Не поняла команду")
                }
            }

            else -> host.setState(CharacterState.IDLE)
        }
    }

    // ------------------------------------------------------------------ //
    // Экран
    // ------------------------------------------------------------------ //

    private fun readScreen(describe: Boolean) {
        host.setState(CharacterState.READING)
        ScreenAnalyzer.read(context) { snap ->
            if (snap == null) {
                host.setState(CharacterState.ERROR)
                host.speak("Не могу прочитать экран: включите службу NOVA в специальных возможностях")
                return@read
            }
            conversation.screenText = snap.text
            val error = ScreenAnalyzer.findError(snap)
            val text = if (describe) {
                ScreenAnalyzer.describe(context, snap) + (error?.let { ". Вижу ошибку: $it" } ?: "")
            } else {
                ScreenAnalyzer.describe(context, snap)
            }
            host.setState(CharacterState.DONE)
            host.speak(text)
            host.showMessage(text)
        }
    }

    // ------------------------------------------------------------------ //
    // Агент
    // ------------------------------------------------------------------ //

    private fun runAgent(goal: String, intents: List<Intent>) {
        if (agent.isRunning()) {
            host.showMessage("Я уже занят задачей. Скажите «стоп», чтобы начать новую.")
            return
        }
        host.setState(CharacterState.THINK_HARD)

        // Сначала посмотрим на экран — это реальные данные, а не догадки.
        if (intents.any { it.type == IntentType.GAME_HELP }) {
            GameAssistant(context).analyze { host.showMessage(it) }
        }
        if (intents.any { it.type == IntentType.EDUCATION_HELP }) {
            EducationAssistant(context, search).describeScreen { host.showMessage(it) }
        }

        agent.run(goal, intents, object : TaskAgent.Callbacks {
            override fun onState(state: CharacterState) { host.setState(state) }
            override fun onProgress(step: com.nova.assistant.core.TaskStep, index: Int, total: Int) {
                host.showProgress("Шаг $index из $total: ${step.title}")
            }
            override fun onMessage(text: String) { host.showMessage(text); host.speak(text) }
            override fun onConfirm(question: String, onYes: () -> Unit, onNo: () -> Unit) {
                host.setState(CharacterState.CONFIRM)
                host.askConfirm(question, onYes, onNo)
            }
            override fun onDone(report: String) {
                host.setState(CharacterState.DONE)
                host.speak(report)
                host.showMessage(report)
                host.onTaskFinished(report)
            }
            override fun onError(message: String) {
                host.setState(CharacterState.ERROR)
                host.speak(message)
            }
        })
    }

    fun stopAll() {
        agent.stop(object : TaskAgent.Callbacks {
            override fun onDone(report: String) {
                host.setState(CharacterState.IDLE)
                host.speak("Остановила")
            }
        })
        voice.stopSpeaking()
        if (settings.alwaysListening && !settings.isEco) voice.listenContinuously()
    }

    // ------------------------------------------------------------------ //
    // Таймеры
    // ------------------------------------------------------------------ //

    private fun setTimer(text: String?) {
        val minutes = parseMinutes(text) ?: 5
        val at = System.currentTimeMillis() + minutes * 60_000L
        schedule(at, TimerReceiver.ACTION_TIMER, "Таймер $minutes минут")
        host.setState(CharacterState.DONE)
        host.speak("Таймер на $minutes минут установлен")
    }

    private fun setReminder(text: String?) {
        val minutes = parseMinutes(text) ?: 60
        val at = System.currentTimeMillis() + minutes * 60_000L
        schedule(at, TimerReceiver.ACTION_REMINDER, text ?: "Напоминание")
        host.setState(CharacterState.DONE)
        host.speak("Напомню через $minutes минут")
    }

    private fun parseMinutes(text: String?): Int? {
        if (text.isNullOrBlank()) return null
        val num = Regex("(\\d+)").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        return when {
            text.contains("час") -> num * 60
            text.contains("сек") -> num / 60
            else -> num
        }
    }

    private fun schedule(at: Long, action: String, label: String) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val intent = android.content.Intent(context, TimerReceiver::class.java).setAction(action).putExtra("label", label)
        val pi = PendingIntent.getBroadcast(
            context, action.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } else {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    private fun findPhone(name: String?): String? {
        if (name.isNullOrBlank()) return null
        val phones = PermissionManager.loadContactPhones(context)
        val m = FuzzyMatcher.bestMatch(name, phones.keys, 0.72f) ?: return null
        return phones[m.value]
    }
}
