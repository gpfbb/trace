package com.trackmaster.app.storage

import com.trackmaster.app.core.StaySegment

/**
 * 存储适配器接口
 *
 * 宿主 App 实现此接口对接现有数据库（SQLite / Room / IndexedDB）。
 * Engine 和 Repository 通过此接口进行零侵入存储。
 */
interface StorageAdapter {
    /** 批量插入 Segment */
    suspend fun insertSegments(segments: List<StaySegment>)

    /** 查询指定日期的所有 Segment（按进入日期） */
    suspend fun querySegmentsByDate(dateKey: String): List<StaySegment>

    /** 查询未关闭的 Segment（endTs === null） */
    suspend fun queryOpenSegments(): List<StaySegment>

    suspend fun beginTransaction()
    suspend fun commitTransaction()
    suspend fun rollbackTransaction()
}
