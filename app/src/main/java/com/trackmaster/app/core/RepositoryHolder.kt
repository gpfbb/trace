package com.trackmaster.app.core

import com.trackmaster.app.data.GeoFence
import com.trackmaster.app.data.GeoFenceEvent
import com.trackmaster.app.data.FenceStayRecord
import com.trackmaster.app.model.Category
import com.trackmaster.app.storage.Repository
import com.trackmaster.app.storage.StorageAdapter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Date
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Repository 持有者 + 内存数据管理
 *
 * 统一管理应用层数据：
 * - Repository 实例（Koin 注入）
 * - 内存中的围栏/分类/事件列表（Application 共享）
 * - UI 状态流（围栏高亮、显示等）
 */
class RepositoryHolder(
    private val storageAdapter: StorageAdapter
) {
    companion object {
        // 【Bug修复 H12】geoFenceEvents 上限，防止无限增长导致 O(n) 性能衰减
        // 保留最近 5000 条事件，超出后清理最旧的
        private const val MAX_EVENTS_SIZE = 5000
    }

    val repository: Repository = Repository(storageAdapter)

    // ============================================================
    // 应用层数据列表（由 Application 初始化，跨组件共享）
    // ============================================================

    val geoFences = CopyOnWriteArrayList<GeoFence>()
    val categories = CopyOnWriteArrayList<Category>()
    val fenceEnterTimes = ConcurrentHashMap<String, Date>()
    val fenceStayRecords = CopyOnWriteArrayList<FenceStayRecord>()
    val geoFenceEvents = CopyOnWriteArrayList<GeoFenceEvent>()

    /**
     * 添加围栏事件，带自动清理
     * 当事件数超过 MAX_EVENTS_SIZE 时，删除最旧的 10% 事件
     * 防止 CopyOnWriteArrayList 无限增长导致 filter 等操作 O(n) 衰减
     * 【P0修复】addEvent 和 cleanup 都在 synchronized 块内，避免并发竞态
     */
    private val eventCleanupLock = Any()

    fun addEvent(event: GeoFenceEvent) {
        geoFenceEvents.add(event)
        if (geoFenceEvents.size > MAX_EVENTS_SIZE) {
            // 【P0修复】check-then-cleanup 必须在锁内完成，避免多线程重复清理
            synchronized(eventCleanupLock) {
                // 再次检查（可能另一线程已清理）
                if (geoFenceEvents.size > MAX_EVENTS_SIZE) {
                    val toRemove = (MAX_EVENTS_SIZE * 0.1).toInt().coerceAtLeast(1)
                    val sortedEvents = geoFenceEvents.sortedBy { it.time }
                    for (i in 0 until toRemove) {
                        val idToRemove = sortedEvents[i].id
                        geoFenceEvents.removeIf { it.id == idToRemove }
                    }
                }
            }
        }
    }

    /** 当前所有命中的围栏列表（包括嵌套的父级和子级，按深度排序） */
    val currentMatchedFences = CopyOnWriteArrayList<GeoFence>()

    // ============================================================
    // UI 状态流
    // ============================================================

    /** 围栏状态版本号，用于触发 UI 刷新 */
    private val _fenceStateVersion = MutableStateFlow(0)
    val fenceStateVersion: StateFlow<Int> = _fenceStateVersion

    /** 当前高亮的围栏ID（从灵动岛选择） */
    private val _highlightedFenceId = MutableStateFlow<String?>(null)
    val highlightedFenceId: StateFlow<String?> = _highlightedFenceId

    /** 当前显示的围栏ID（从灵动岛选择，用于只读显示围栏点） */
    private val _displayedFenceId = MutableStateFlow<String?>(null)
    val displayedFenceId: StateFlow<String?> = _displayedFenceId

    // ============================================================
    // 状态操作
    // ============================================================

    /** 通知 UI 围栏状态已更新 */
    fun notifyFenceStateChanged() {
        _fenceStateVersion.value++
    }

    /** 设置高亮围栏ID */
    fun setHighlightedFenceId(fenceId: String?) {
        _highlightedFenceId.value = fenceId
    }

    /** 设置显示围栏ID（只读显示围栏点） */
    fun setDisplayedFenceId(fenceId: String?) {
        _displayedFenceId.value = fenceId
    }
}
