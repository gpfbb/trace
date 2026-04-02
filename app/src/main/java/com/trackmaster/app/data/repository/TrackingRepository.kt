package com.trackmaster.app.data.repository

import com.trackmaster.app.data.dao.*
import com.trackmaster.app.data.entity.*
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TrackingRepository @Inject constructor(
    private val trackSessionDao: TrackSessionDao,
    private val trackPointDao: TrackPointDao,
    private val stayPointDao: StayPointDao,
    private val geofenceDao: GeofenceDao,
    private val geofenceEventDao: GeofenceEventDao
) {
    suspend fun insertSession(session: TrackSession): Long = trackSessionDao.insert(session)
    suspend fun updateSession(session: TrackSession) = trackSessionDao.update(session)
    suspend fun deleteSession(session: TrackSession) = trackSessionDao.delete(session)
    suspend fun getSessionById(id: Long): TrackSession? = trackSessionDao.getSessionById(id)
    suspend fun getUncompletedSession(): TrackSession? = trackSessionDao.getUncompletedSession()
    fun getAllSessions(): Flow<List<TrackSession>> = trackSessionDao.getAllSessions()
    fun getSessionsBetween(start: Long, end: Long): Flow<List<TrackSession>> = 
        trackSessionDao.getSessionsBetween(start, end)

    suspend fun insertPoint(point: TrackPoint): Long = trackPointDao.insert(point)
    suspend fun insertPoints(points: List<TrackPoint>) = trackPointDao.insertAll(points)
    fun getPointsBySession(sessionId: Long): Flow<List<TrackPoint>> = 
        trackPointDao.getPointsBySession(sessionId)
    suspend fun getPointsBySessionSync(sessionId: Long): List<TrackPoint> = 
        trackPointDao.getPointsBySessionSync(sessionId)

    suspend fun insertStayPoint(point: StayPoint): Long = stayPointDao.insert(point)
    suspend fun updateStayPoint(point: StayPoint) = stayPointDao.update(point)
    fun getStayPointsBySession(sessionId: Long): Flow<List<StayPoint>> = 
        stayPointDao.getStayPointsBySession(sessionId)
    suspend fun getLatestStayPoint(sessionId: Long): StayPoint? = 
        stayPointDao.getLatestStayPoint(sessionId)

    suspend fun insertGeofence(geofence: Geofence): Long = geofenceDao.insert(geofence)
    suspend fun updateGeofence(geofence: Geofence) = geofenceDao.update(geofence)
    suspend fun deleteGeofence(geofence: Geofence) = geofenceDao.delete(geofence)
    fun getAllGeofences(): Flow<List<Geofence>> = geofenceDao.getAllGeofences()
    suspend fun getEnabledGeofences(): List<Geofence> = geofenceDao.getEnabledGeofences()
    suspend fun getGeofenceById(id: Long): Geofence? = geofenceDao.getGeofenceById(id)

    suspend fun insertGeofenceEvent(event: GeofenceEvent): Long = geofenceEventDao.insert(event)
    fun getEventsByGeofence(geofenceId: Long): Flow<List<GeofenceEvent>> = 
        geofenceEventDao.getEventsByGeofence(geofenceId)
}
