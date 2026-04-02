package com.trackmaster.app.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "track_sessions")
data class TrackSession(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val startTime: Long,
    var endTime: Long? = null,
    var totalDistance: Float = 0f,
    var avgSpeed: Float = 0f,
    var maxSpeed: Float = 0f,
    var isCompleted: Boolean = false,
    val name: String? = null
)
