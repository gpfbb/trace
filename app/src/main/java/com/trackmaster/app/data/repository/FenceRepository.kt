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

    /** 批量把所有围栏的填充透明度改成 [alpha]。 */
    fun updateAllFencesAlpha(alpha: Float)

    /**
     * 用数据库里的最新数据整体替换内存中的围栏列表。
     *
     * 批量修改（随机配色、统一透明度）之后调用，让内存和数据库重新一致。
     * 不要由 UI 直接操作 `geoFences`——那样会绕过 Engine 的刷新时机。
     */
    fun reloadFencesFromDatabase()
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

    override fun updateAllFencesAlpha(alpha: Float) {
        app.dbHelper.updateAllFencesAlpha(alpha)
    }

    override fun reloadFencesFromDatabase() {
        app.geoFences.clear()
        app.geoFences.addAll(app.dbHelper.getAllFences())
    }
}
