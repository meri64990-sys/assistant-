package com.nova.assistant.notes

import com.nova.assistant.core.Note
import com.nova.assistant.core.NotesQuery
import com.nova.assistant.memory.NoteDao
import com.nova.assistant.memory.NoteEntity

/**
 * Заметки: создание голосом, авто-название, поиск, категории, избранное.
 * Хранятся локально и переживают перезапуск.
 */
class NotesManager(private val dao: NoteDao) {

    suspend fun create(content: String, category: String = "Заметки"): Note {
        val now = System.currentTimeMillis()
        val title = NotesQuery.autoTitle(content)
        val id = dao.insert(
            NoteEntity(
                title = title,
                content = content,
                category = category,
                favorite = false,
                createdAt = now,
                updatedAt = now
            )
        )
        return Note(id, title, content, category, false, now, now)
    }

    suspend fun all(): List<Note> = dao.all().map { it.toNote() }

    suspend fun search(query: String): List<Note> = NotesQuery.search(all(), query)

    suspend fun favorites(): List<Note> = NotesQuery.favorites(all())

    suspend fun byCategory(category: String): List<Note> = all().filter { it.category == category }

    suspend fun categories(): List<String> = NotesQuery.categories(all())

    suspend fun setFavorite(note: Note, favorite: Boolean) {
        dao.byId(note.id)?.let {
            dao.update(it.copy(favorite = favorite, updatedAt = System.currentTimeMillis()))
        }
    }

    suspend fun update(note: Note, newContent: String) {
        dao.byId(note.id)?.let {
            dao.update(
                it.copy(
                    content = newContent,
                    title = NotesQuery.autoTitle(newContent),
                    updatedAt = System.currentTimeMillis()
                )
            )
        }
    }

    suspend fun delete(note: Note) {
        dao.byId(note.id)?.let { dao.delete(it) }
    }

    suspend fun deleteAll() = dao.clear()

    /** Поиск с учётом даты («что я записывал вчера»). */
    suspend fun findByDate(phrase: String): List<Note> {
        val range = NotesQuery.dateRangeFromPhrase(phrase, System.currentTimeMillis()) ?: return emptyList()
        return NotesQuery.filterByDate(all(), range.first, range.second)
    }

    private fun NoteEntity.toNote() = Note(id, title, content, category, favorite, createdAt, updatedAt)
}
