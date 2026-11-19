package com.nova.assistant.core

/**
 * Краткосровая память диалога. Живёт в памяти процесса и дополнительно
 * сохраняется в SQLite (через [com.nova.assistant.memory.MemoryStore]),
 * чтобы контекст не терялся при перезапуске сервиса.
 */
data class ConversationContext(
    var lastAppName: String? = null,
    var lastAppPackage: String? = null,
    var lastContact: String? = null,
    var lastIntentType: IntentType? = null,
    var lastText: String? = null,
    var lastQuery: String? = null,
    /** Текст, который NOVA прочитала с экрана последним. */
    var screenText: String? = null,
    /** Пакет приложения, которое сейчас на экране. */
    var currentPackage: String? = null,
    /** Незавершённое действие, ждущее подтверждения пользователя. */
    var pendingConfirmation: Intent? = null,
    /** Текст, который пользователь только что продиктовал (для «запиши это»). */
    var lastDictated: String? = null,
    var turnCount: Int = 0
) {
    fun touch() { turnCount++ }

    /** Полезно для ответов: «напиши ему» — кто он? */
    fun describeContact(): String? = lastContact

    fun clearEphemeral() {
        lastDictated = null
    }
}

/** Долгосрочная память: одна запись. */
data class MemoryEntry(
    val id: Long = 0,
    val key: String,
    val value: String,
    val kind: String,
    val createdAt: Long
)

/** Заметка пользователя. */
data class Note(
    val id: Long = 0,
    val title: String,
    val content: String,
    val category: String = "Заметки",
    val favorite: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long = createdAt
)

/** История задачи. */
data class TaskRecord(
    val id: Long = 0,
    val goal: String,
    val status: String,
    val planJson: String,
    val report: String,
    val createdAt: Long,
    val finishedAt: Long = createdAt
)

/** Частота использования приложения — для подсказок «частые приложения». */
data class AppUsage(
    val packageName: String,
    val label: String,
    val launches: Int,
    val lastUsed: Long
)
