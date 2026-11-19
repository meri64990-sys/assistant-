package com.nova.assistant.memory

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Update
import android.content.Context
import androidx.room.Room

// --------------------------------------------------------------------- //
// Сущности
// --------------------------------------------------------------------- //

@Entity(tableName = "notes")
data class NoteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val content: String,
    val category: String,
    val favorite: Boolean,
    val createdAt: Long,
    val updatedAt: Long
)

@Entity(tableName = "tasks")
data class TaskEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val goal: String,
    val status: String,
    val planJson: String,
    val report: String,
    val createdAt: Long,
    val finishedAt: Long
)

@Entity(tableName = "memory")
data class MemoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val key: String,
    val value: String,
    val kind: String,
    val createdAt: Long
)

@Entity(tableName = "app_usage")
data class AppUsageEntity(
    @PrimaryKey val packageName: String,
    val label: String,
    val launches: Int,
    val lastUsed: Long
)

// --------------------------------------------------------------------- //
// DAO
// --------------------------------------------------------------------- //

@Dao
interface NoteDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(n: NoteEntity): Long

    @Update
    suspend fun update(n: NoteEntity)

    @Delete
    suspend fun delete(n: NoteEntity)

    @Query("SELECT * FROM notes ORDER BY updatedAt DESC")
    suspend fun all(): List<NoteEntity>

    @Query("SELECT * FROM notes WHERE id = :id")
    suspend fun byId(id: Long): NoteEntity?

    @Query("DELETE FROM notes")
    suspend fun clear()
}

@Dao
interface TaskDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(t: TaskEntity): Long

    @Update
    suspend fun update(t: TaskEntity)

    @Query("SELECT * FROM tasks ORDER BY createdAt DESC LIMIT 200")
    suspend fun recent(): List<TaskEntity>

    @Query("SELECT * FROM tasks WHERE id = :id")
    suspend fun byId(id: Long): TaskEntity?

    @Query("DELETE FROM tasks")
    suspend fun clear()
}

@Dao
interface MemoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(m: MemoryEntity): Long

    @Update
    suspend fun update(m: MemoryEntity)

    @Query("SELECT * FROM memory WHERE kind = :kind ORDER BY createdAt DESC")
    suspend fun byKind(kind: String): List<MemoryEntity>

    @Query("SELECT * FROM memory WHERE `key` = :key LIMIT 1")
    suspend fun byKey(key: String): MemoryEntity?

    @Query("SELECT * FROM memory ORDER BY createdAt DESC")
    suspend fun all(): List<MemoryEntity>

    @Delete
    suspend fun delete(m: MemoryEntity)

    @Query("DELETE FROM memory")
    suspend fun clear()
}

@Dao
interface AppUsageDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(a: AppUsageEntity)

    @Query("SELECT * FROM app_usage ORDER BY launches DESC LIMIT 30")
    suspend fun top(): List<AppUsageEntity>

    @Query("SELECT * FROM app_usage WHERE packageName = :pkg LIMIT 1")
    suspend fun byPackage(pkg: String): AppUsageEntity?
}

// --------------------------------------------------------------------- //
// База
// --------------------------------------------------------------------- //

@Database(
    entities = [NoteEntity::class, TaskEntity::class, MemoryEntity::class, AppUsageEntity::class],
    version = 1,
    exportSchema = false
)
abstract class MemoryStore : RoomDatabase() {
    abstract fun noteDao(): NoteDao
    abstract fun taskDao(): TaskDao
    abstract fun memoryDao(): MemoryDao
    abstract fun appUsageDao(): AppUsageDao

    companion object {
        @Volatile private var INSTANCE: MemoryStore? = null

        fun get(context: Context): MemoryStore = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(
                context.applicationContext,
                MemoryStore::class.java,
                "nova.db"
            )
                .fallbackToDestructiveMigration()
                .build()
                .also { INSTANCE = it }
        }
    }
}
