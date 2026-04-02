package com.trackmaster.app.data.dao

import androidx.room.*
import com.trackmaster.app.data.entity.StayPoint
import kotlinx.coroutines.flow.Flow

@Dao
interface StayPointDao {
    @Insert
    suspend fun insert(point: StayPoint): Long

    @Update
    suspend fun update(point: StayPoint)

    @Query("SELECT * FROM stay_points WHERE sessionId = :sessionId ORDER BY startTime ASC")
    fun getStayPointsBySession(sessionId: Long): Flow<List<StayPoint>>

    @Query("SELECT * FROM stay_points WHERE sessionId = :sessionId ORDER BY startTime DESC LIMIT 1")
    suspend fun getLatestStayPoint(sessionId: Long): StayPoint?

    @Delete
    suspend fun delete(point: StayPoint)
}
