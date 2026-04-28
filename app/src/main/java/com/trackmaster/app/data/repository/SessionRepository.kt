package com.trackmaster.app.data.repository

import com.trackmaster.app.DailyStayRecord
import com.trackmaster.app.StayDurationManager
import com.trackmaster.app.TrackMasterApplication
import com.trackmaster.app.data.GeoFence
import com.trackmaster.app.data.GeoFenceEvent
import kotlinx.coroutines.flow.StateFlow
import java.util.Date

interface SessionRepository {
    val currentMatchedFences: List<GeoFence>
    val fenceEnterTimes: Map<String, Date>
    val geoFenceEvents: List<GeoFenceEvent>
    val fenceStateVersion: StateFlow<Int>
    val stayDurationManager: StayDurationManager
    fun getDailyStayRecordsByDate(dateKey: String): List<DailyStayRecord>
}

class AppSessionRepository(
    private val app: TrackMasterApplication
) : SessionRepository {
    override val currentMatchedFences: List<GeoFence> get() = app.currentMatchedFences
    override val fenceEnterTimes: Map<String, Date> get() = app.fenceEnterTimes
    override val geoFenceEvents: List<GeoFenceEvent> get() = app.geoFenceEvents
    override val fenceStateVersion: StateFlow<Int> get() = app.fenceStateVersion
    override val stayDurationManager: StayDurationManager get() = app.stayDurationManager
    override fun getDailyStayRecordsByDate(dateKey: String): List<DailyStayRecord> =
        app.dbHelper.getDailyStayRecordsByDate(dateKey)
}
