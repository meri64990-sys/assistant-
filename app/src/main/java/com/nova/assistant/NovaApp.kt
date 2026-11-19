package com.nova.assistant

import android.app.Application
import android.content.Context
import com.nova.assistant.core.CommandParser
import com.nova.assistant.core.ConversationContext
import com.nova.assistant.core.IntentResolver
import com.nova.assistant.core.LocalAIEngine
import com.nova.assistant.core.RuleBasedEngine
import com.nova.assistant.memory.MemoryRepository
import com.nova.assistant.memory.MemoryStore
import com.nova.assistant.notes.NotesManager
import com.nova.assistant.settings.SettingsManager
import com.nova.assistant.tasks.TaskHistory

/**
 * Точка входа. Держит единственных владельцев зависимостей, чтобы все модули
 * (сервисы, экраны, оверлей) работали с одной и той же локальной базой и
 * настройками.
 */
class NovaApp : Application() {

    override fun onCreate() {
        super.onCreate()
        appContext = this
        settings = SettingsManager(this)
        db = MemoryStore.get(this)
        notes = NotesManager(db.noteDao())
        history = TaskHistory(db.taskDao())
        memory = MemoryRepository(db.memoryDao())
        localAi = RuleBasedEngine()
        parser = CommandParser()
        resolver = IntentResolver(parser)
        conversation = ConversationContext()
    }

    companion object {
        @Volatile private var appContext: Context? = null
        @Volatile var settings: SettingsManager? = null
            private set
        @Volatile var db: MemoryStore? = null
            private set
        @Volatile var notes: NotesManager? = null
            private set
        @Volatile var history: TaskHistory? = null
            private set
        @Volatile var memory: MemoryRepository? = null
            private set
        @Volatile var localAi: LocalAIEngine? = null
            private set
        @Volatile var parser: CommandParser? = null
            private set
        @Volatile var resolver: IntentResolver? = null
            private set

        /** Краткосрочная память диалога — общая для всех модулей. */
        @Volatile var conversation: ConversationContext? = null

        fun ctx(): Context = appContext ?: throw IllegalStateException("NovaApp не инициализировано")
    }
}
