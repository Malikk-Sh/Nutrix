package com.nutrix.app.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.nutrix.app.database.entity.NoteEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface NoteDao {
    @Query("SELECT * FROM notes WHERE localDate = :day ORDER BY epochMillis")
    fun observeDay(day: Long): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE localDate BETWEEN :from AND :to ORDER BY epochMillis")
    suspend fun getRange(from: Long, to: Long): List<NoteEntity>

    @Query("SELECT * FROM notes WHERE localDate BETWEEN :from AND :to ORDER BY epochMillis")
    fun observeRange(from: Long, to: Long): Flow<List<NoteEntity>>

    @Query("SELECT DISTINCT localDate FROM notes WHERE localDate BETWEEN :from AND :to")
    fun observeDays(from: Long, to: Long): Flow<List<Long>>

    @Query("SELECT * FROM notes WHERE id = :id")
    suspend fun get(id: Long): NoteEntity?

    @Insert
    suspend fun insert(note: NoteEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertWithId(note: NoteEntity): Long

    @Update
    suspend fun update(note: NoteEntity)

    @Delete
    suspend fun delete(note: NoteEntity)

    @Query("SELECT * FROM notes ORDER BY epochMillis DESC")
    suspend fun allNotes(): List<NoteEntity>
}
