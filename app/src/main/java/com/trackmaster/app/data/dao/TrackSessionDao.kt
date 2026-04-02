package com.trackmaster.app.data.dao

import androidx.room.*
import com.trackmaster.app.data.entity.TrackSession
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackSessionDao {
    @Insert
    suspend fun insert(session: TrackSession): Long

    @Update
    suspend fun update(session: TrackSession)

    @Delete
    suspend fun delete(session: TrackSession)

    @Query("DELETE FROM track_sessions WHERE id = :sessionId")
    suspend fun deleteById(sessionId: Long)

    @Query("SELECT * FROM track_sessions WHERE id = :sessionId")
    suspend fun getSessionById(sessionId: Long): TrackSession?

    @Query("SELECT * FROM track_sessions WHERE isCompleted = 0 ORDER BY startTime DESC LIMIT 1")
    suspend fun getUncompletedSession(): TrackSession?

    @Query("SELECT * FROM track_sessions ORDER BY startTime DESC")
    fun getAllSessions(): Flow<List<TrackSession>>

    @Query("SELECT * FROM track_sessions WHERE startTime BETWEEN :startTime AND :endTime ORDER BY startTime DESC")
    fun getSessionsBetween(startTime: Long, endTime: Long): Flow<List<TrackSession>>
}
