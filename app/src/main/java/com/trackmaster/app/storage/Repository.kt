package com.trackmaster.app.storage

import com.trackmaster.app.core.DailySummary
import com.trackmaster.app.core.StaySegment
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 存储仓库 — 内存缓存 + 批量事务写入
 *
 * 1. Segment 写入先进内存队列，达到 batchSize 后批量 flush
 * 2. 读取时内存缓存优先，减少底层存储访问
 * 3. 所有操作通过 StorageAdapter 委托
 */
class Repository(
    private val adapter: StorageAdapter,
    private val batchSize: Int = 5,
) {
    private val writeBuffer = CopyOnWriteArrayList<StaySegment>()
    private val segmentCache = mutableMapOf<String, List<StaySegment>>()
    private val summaryCache = mutableMapOf<String, List<DailySummary>>()

    // 使用同步锁保护 flush 操作，避免并发 flush
    private val flushLock = Any()

    /** 追加 Segment 到写入缓冲区，达到 batchSize 自动 flush */
    suspend fun enqueueSegment(seg: StaySegment) {
        writeBuffer.add(seg)
        segmentCache.remove(formatDateKey(seg.startTs))
        if (writeBuffer.size >= batchSize) {
            flush()
        }
    }

    /** 批量 flush 到底层存储（事务保护） */
    suspend fun flush() {
        val batch: ArrayList<StaySegment>
        synchronized(flushLock) {
            if (writeBuffer.isEmpty()) return
            batch = ArrayList(writeBuffer)
            writeBuffer.clear()
        }
        // 在临界区外执行数据库操作
        try {
            adapter.beginTransaction()
            adapter.insertSegments(batch)
            adapter.commitTransaction()
            // 【修复】清除本次写入相关的缓存，让下次查询重新填充
            batch.forEach { seg ->
                segmentCache.remove(formatDateKey(seg.startTs))
            }
        } catch (e: Exception) {
            adapter.rollbackTransaction()
            synchronized(flushLock) {
                writeBuffer.addAll(0, batch) // 放回重试
            }
            throw e
        }
    }

    /** 按日期获取 Segment（缓存优先） */
    suspend fun getSegmentsByDate(dateKey: String): List<StaySegment> {
        segmentCache[dateKey]?.let { return it }
        val dbSegs = adapter.querySegmentsByDate(dateKey)
        // Merge with pending writeBuffer segments for same date
        val pendingSegs = writeBuffer.filter { formatDateKey(it.startTs) == dateKey }
        val merged = (dbSegs + pendingSegs).sortedBy { it.startTs }
        segmentCache[dateKey] = merged
        return merged
    }

    /** 获取未关闭的 Segment */
    suspend fun getOpenSegments(): List<StaySegment> =
        adapter.queryOpenSegments() ?: emptyList()

    /** 保存日汇总（T+1 聚合产物） */
    suspend fun saveDailySummaries(dateKey: String, summaries: List<DailySummary>) {
        adapter.beginTransaction()
        try {
            adapter.saveDailySummaries(summaries)
            adapter.commitTransaction()
        } catch (e: Exception) {
            adapter.rollbackTransaction()
            throw e
        }
        summaryCache[dateKey] = summaries
    }

    /** 读取日汇总 */
    suspend fun getDailySummaries(dateKey: String): List<DailySummary> {
        summaryCache[dateKey]?.let { return it }
        val s = adapter.queryDailySummary(dateKey)
        summaryCache[dateKey] = s
        return s
    }

    /** 清除所有缓存 */
    fun clearCache() {
        segmentCache.clear()
        summaryCache.clear()
    }

    /** 资源释放 */
    suspend fun dispose() {
        flush()
        clearCache()
    }

    companion object {
        fun formatDateKey(ts: Long): String {
            // 【MEDIUM修复】使用 Locale.US 确保日期格式化不受系统语言影响
            val cal = java.util.Calendar.getInstance(Locale.US)
            cal.timeInMillis = ts
            val y = cal.get(java.util.Calendar.YEAR)
            val m = cal.get(java.util.Calendar.MONTH) + 1
            val d = cal.get(java.util.Calendar.DAY_OF_MONTH)
            return "$y-${m.toString().padStart(2, '0')}-${d.toString().padStart(2, '0')}"
        }
    }
}
