package com.trackmaster.app.data.repository

import com.trackmaster.app.DailyStayRecord
import com.trackmaster.app.StayDurationManager
import com.trackmaster.app.TrackMasterApplication
import com.trackmaster.app.data.GeoFence
import com.trackmaster.app.data.GeoFenceAction
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

    /**
     * 一次查出日期区间内的所有停留记录。
     *
     * 月度统计原来按天循环查 31 次，改成一次范围查询。
     */
    fun getDailyStayRecordsByDateRange(startDateKey: String, endDateKey: String): List<DailyStayRecord>

    /** 围栏 id → 名称。用于修正旧数据里被写成 id 的围栏名。 */
    fun getFenceNamesById(): Map<String, String>

    /** 统计时间范围内指定动作的事件数（进入/离开次数） */
    fun countEventsByAction(action: GeoFenceAction, startTime: Date, endTime: Date): Int
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

    override fun getDailyStayRecordsByDateRange(
        startDateKey: String,
        endDateKey: String
    ): List<DailyStayRecord> =
        app.dbHelper.getDailyStayRecordsByDateRange(startDateKey, endDateKey)

    override fun getFenceNamesById(): Map<String, String> =
        app.geoFences.associate { it.id to it.name }

    override fun countEventsByAction(action: GeoFenceAction, startTime: Date, endTime: Date): Int =
        app.dbHelper.countEventsByAction(action, startTime, endTime)
}
