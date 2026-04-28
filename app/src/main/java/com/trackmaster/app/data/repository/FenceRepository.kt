package com.trackmaster.app.data.repository

import com.trackmaster.app.TrackMasterApplication
import com.trackmaster.app.data.GeoFence

interface FenceRepository {
    val allFences: MutableList<GeoFence>
    fun queryAllFences(): List<GeoFence>
    fun insertFence(fence: GeoFence)
    fun updateFence(fence: GeoFence): Int
    fun deleteFence(fenceId: String)
    fun refreshEngineFences()
    fun checkAndUpdateFenceState()
}

class AppFenceRepository(
    private val app: TrackMasterApplication
) : FenceRepository {
    override val allFences: MutableList<GeoFence> get() = app.geoFences
    override fun queryAllFences(): List<GeoFence> = app.dbHelper.getAllFences()
    override fun insertFence(fence: GeoFence) { app.dbHelper.insertFence(fence) }
    override fun updateFence(fence: GeoFence): Int = app.dbHelper.updateFence(fence)
    override fun deleteFence(fenceId: String) { app.dbHelper.deleteFence(fenceId) }
    override fun refreshEngineFences() { app.refreshEngineFences() }
    override fun checkAndUpdateFenceState() { app.checkAndUpdateFenceState() }
}
