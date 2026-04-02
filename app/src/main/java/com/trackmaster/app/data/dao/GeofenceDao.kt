package com.trackmaster.app.data.dao

import androidx.room.*
import com.trackmaster.app.data.entity.Geofence
import kotlinx.coroutines.flow.Flow

@Dao
interface GeofenceDao {
    @Insert
    suspend fun insert(geofence: Geofence): Long

    @Update
    suspend fun update(geofence: Geofence)

    @Delete
    suspend fun delete(geofence: Geofence)

    @Query("SELECT * FROM geofences ORDER BY createdAt DESC")
    fun getAllGeofences(): Flow<List<Geofence>>

    @Query("SELECT * FROM geofences WHERE isEnabled = 1")
    suspend fun getEnabledGeofences(): List<Geofence>

    @Query("SELECT * FROM geofences WHERE id = :id")
    suspend fun getGeofenceById(id: Long): Geofence?
}
