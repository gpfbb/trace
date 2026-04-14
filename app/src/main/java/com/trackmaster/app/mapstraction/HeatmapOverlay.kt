package com.trackmaster.app.mapstraction

import com.trackmaster.app.core.DailySummary
import com.trackmaster.app.core.Geofence

/**
 * 热力图叠加层接口
 *
 * 围栏多边形按停留时长着色，在地图上形成热力图效果。
 */
interface HeatmapOverlay {

    /**
     * 更新热力图覆盖层
     *
     * @param fences 核心围栏列表
     * @param summaries 当日汇总（含停留时长）
     * @param maxDurationMin 最大时长（用于归一化），0 = 自动计算
     */
    fun update(
        fences: List<Geofence>,
        summaries: List<DailySummary>,
        maxDurationMin: Int = 0,
    )

    /** 清除所有覆盖层 */
    fun clear()
}
