package com.trackmaster.app.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "geofence_events",
    indices = [Index("geofenceId")],
    foreignKeys = [ForeignKey(
        entity = Geofence::class,
        parentColumns = ["id"],
        childColumns = ["geofenceId"],
        onDelete = ForeignKey.CASCADE
    )]
)
data class GeofenceEvent(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val geofenceId: Long,
    val eventType: String,
    val latitude: Double,
    val longitude: Double,
    val timestamp: Long
)
