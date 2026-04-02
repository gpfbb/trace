package com.trackmaster.app.data.dao

import androidx.room.*
import com.trackmaster.app.data.entity.TrackPoint
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackPointDao {
    @Insert
    suspend fun insert(point: TrackPoint): Long

    @Insert
    suspend fun insertAll(points: List<TrackPoint>)

    @Query("SELECT * FROM track_points WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    fun getPointsBySession(sessionId: Long): Flow<List<TrackPoint>>

    @Query("SELECT * FROM track_points WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    suspend fun getPointsBySessionSync(sessionId: Long): List<TrackPoint>

    @Query("DELETE FROM track_points WHERE sessionId = :sessionId")
    suspend fun deleteBySession(sessionId: Long)
}
