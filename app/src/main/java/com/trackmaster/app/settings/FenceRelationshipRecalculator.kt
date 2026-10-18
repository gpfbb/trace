package com.trackmaster.app.settings

import com.trackmaster.app.TrackMasterApplication
import com.trackmaster.app.core.GeoFenceType
import com.trackmaster.app.core.GeoPoint
import com.trackmaster.app.core.Geofence
import com.trackmaster.app.utils.AppLog
import com.trackmaster.app.utils.geometry.detectFenceIntersections
import com.trackmaster.app.utils.geometry.isFenceContainedByFence

/**
 * 围栏父子关系的重新计算。
 *
 * ## 为什么这段逻辑要单独拿出来
 *
 * 原来它整段写在 `SettingsPage` 里一个 `AlertDialog` 的确认按钮回调里：
 * 100 多行算法（把 UI 模型转成 core 模型 → 检测相交 → 两两检测包含关系 →
 * 写回 FenceTreeManager），中间还夹着日志和字符串模板替换。
 *
 * 这样写有三个问题：
 * 1. 没法单独测试——要跑它必须起来一个 Compose 界面并点开对话框
 * 2. 按钮里嵌 100 行算法，任何人改这段都要先读完整个屏幕
 * 3. 状态（`recalculateMessage` / `recalculateIsSuccess`）和算法纠缠在一起
 *
 * 现在算法在这里，UI 只负责调用和展示结果。
 */
class FenceRelationshipRecalculator(
    private val app: TrackMasterApplication
) {

    /** 计算结果的两种可能。 */
    sealed interface Result {
        /**
         * 存在相交的围栏，没有执行任何修改。
         *
         * 相交的围栏之间"谁包含谁"没有意义，强行计算会得到互相矛盾的结果，
         * 所以宁可什么都不做，让用户先去修围栏。
         */
        data class HasIntersections(val pairCount: Int) : Result

        /** 计算完成，建立了 [relationshipCount] 对父子关系。 */
        data class Success(val relationshipCount: Int) : Result
    }

    /**
     * 执行重新计算。
     *
     * 注意这是**破坏性操作**：会先清空现有的父子关系再重建。
     * 调用方应确保用户已经确认过。
     */
    fun recalculate(): Result {
        val coreFences = app.geoFences.mapNotNull { it.toCoreFence() }

        // 第一步：相交检测。相交的围栏不能建立父子关系。
        val intersections = detectFenceIntersections(coreFences)
        AppLog.d(TAG, "相交检测完成，发现 ${intersections.size} 对相交")
        if (intersections.isNotEmpty()) {
            return Result.HasIntersections(intersections.size)
        }

        // 第二步：清空后按包含关系重建
        app.fenceTreeManager.clearAll()
        var relationshipCount = 0

        for (fenceA in coreFences) {
            for (fenceB in coreFences) {
                if (fenceA.id == fenceB.id) continue
                val bInsideA = isFenceContainedByFence(fenceB, fenceA)
                val aInsideB = isFenceContainedByFence(fenceA, fenceB)
                // 只有"单向包含"才算父子。互相包含说明两个围栏几乎重合，
                // 建立关系会让叶子优先的计时逻辑出现歧义。
                if (bInsideA && !aInsideB) {
                    app.fenceTreeManager.setParent(fenceB.id, fenceA.id)
                    relationshipCount++
                }
            }
        }

        AppLog.d(TAG, "重新计算完成，建立了 $relationshipCount 对父子关系")
        return Result.Success(relationshipCount)
    }

    private fun com.trackmaster.app.data.GeoFence.toCoreFence(): Geofence? {
        val coreType = try {
            GeoFenceType.valueOf(type.name)
        } catch (e: Exception) {
            return null
        }

        val centerPoint: GeoPoint? = when (coreType) {
            GeoFenceType.CIRCLE -> center?.let { GeoPoint(it.latitude, it.longitude) }
            GeoFenceType.POLYGON -> {
                // 多边形没有显式圆心，用顶点平均值作重心
                val pts = points
                if (pts != null && pts.size >= 3) {
                    GeoPoint(
                        pts.sumOf { it.latitude } / pts.size,
                        pts.sumOf { it.longitude } / pts.size
                    )
                } else {
                    null
                }
            }
        }

        return Geofence(
            id = id,
            name = name,
            type = coreType,
            polygon = points?.map { GeoPoint(it.latitude, it.longitude) } ?: emptyList(),
            center = centerPoint,
            radius = radius?.toDouble()
        )
    }

    private companion object {
        const val TAG = "FenceRelationshipRecalculator"
    }
}
