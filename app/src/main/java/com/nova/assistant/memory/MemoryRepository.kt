package com.nova.assistant.memory

import com.nova.assistant.core.FuzzyMatcher

/**
 * Долгосрочная память: предпочтения, факты, незавершённые действия.
 * Всё хранится локально в SQLite.
 */
class MemoryRepository(private val dao: MemoryDao) {

    suspend fun remember(key: String, value: String, kind: String = "fact") {
        val existing = dao.byKey(key)
        if (existing != null) {
            dao.update(existing.copy(value = value, createdAt = System.currentTimeMillis()))
        } else {
            dao.insert(MemoryEntity(key = key, value = value, kind = kind, createdAt = System.currentTimeMillis()))
        }
    }

    suspend fun recall(key: String): String? = dao.byKey(key)?.value

    suspend fun byKind(kind: String): List<MemoryEntity> = dao.byKind(kind)

    suspend fun search(query: String): List<MemoryEntity> {
        val all = dao.all()
        val q = FuzzyMatcher.normalize(query)
        if (q.isEmpty()) return all
        return all.mapNotNull { m ->
            val s = maxOf(
                FuzzyMatcher.score(q, m.key),
                FuzzyMatcher.score(q, m.value)
            )
            if (s >= 0.55f) m to s else null
        }.sortedByDescending { it.second }.map { it.first }
    }

    suspend fun forget(key: String) {
        dao.byKey(key)?.let { dao.delete(it) }
    }

    suspend fun clear() = dao.clear()

    // ------------------------------------------------------------------ //
    // Незавершённые задачи (используется агентом)
    // ------------------------------------------------------------------ //

    suspend fun saveProgress(taskId: Long, json: String) = remember("task_$taskId", json, "progress")

    suspend fun loadProgress(taskId: Long): String? = recall("task_$taskId")

    suspend fun dropProgress(taskId: Long) = forget("task_$taskId")
}
