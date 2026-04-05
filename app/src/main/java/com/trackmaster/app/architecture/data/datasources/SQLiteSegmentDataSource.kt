package com.trackmaster.app.architecture.data.datasources

import com.trackmaster.app.GeoFenceDatabaseHelper
import com.trackmaster.app.architecture.domain.models.StaySegment

/**
 * SQLite 停留片段数据源实现
 */
class SQLiteSegmentDataSource(private val dbHelper: GeoFenceDatabaseHelper) : SegmentDataSource {
    override suspend fun enqueueSegment(segment: StaySegment) {
        // 这里可以实现将片段写入数据库的逻辑
        // 目前使用现有的数据库结构
        // 实际实现可能需要创建新的表或使用现有表
    }

    override suspend fun getOpenSegments(): List<StaySegment> {
        // 从数据库获取未关闭的片段
        // 目前返回空列表，需要根据实际数据库结构实现
        return emptyList()
    }

    override suspend fun flush() {
        // 刷新数据到数据库
    }
}