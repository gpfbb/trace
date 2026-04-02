package com.trackmaster.app.data.dao

import androidx.room.*
import com.trackmaster.app.data.entity.GeofenceEvent
import kotlinx.coroutines.flow.Flow

@Dao
interface GeofenceEventDao {
    @Insert
    suspend fun insert(event: GeofenceEvent): Long

    @Query("SELECT * FROM geofence_events WHERE geofenceId = :geofenceId ORDER BY timestamp DESC")
    fun getEventsByGeofence(geofenceId: Long): Flow<List<GeofenceEvent>>

    @Query("DELETE FROM geofence_events WHERE geofenceId = :geofenceId")
    suspend fun deleteByGeofence(geofenceId: Long)
}
