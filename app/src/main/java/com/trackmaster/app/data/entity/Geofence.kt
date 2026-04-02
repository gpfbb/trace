package com.trackmaster.app.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "geofences")
data class Geofence(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val pointsJson: String,
    val isEnabled: Boolean = true,
    val isInside: Boolean = false,
    val color: Int = 0xFF4CAF50.toInt(),
    val createdAt: Long = System.currentTimeMillis()
)
