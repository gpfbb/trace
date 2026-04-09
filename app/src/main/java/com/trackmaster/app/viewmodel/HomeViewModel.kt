package com.trackmaster.app.viewmodel

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trackmaster.app.FenceStayRecord
import com.trackmaster.app.TrackMasterApplication
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 首页 ViewModel - 管理仪表盘数据聚合
 *
 * 职责：
 * - 管理当前围栏状态和停留时长
 * - 管理今日/本周统计数据
 * - 管理月度 TOP 围栏
 */
class HomeViewModel(
    private val application: Application
) : ViewModel() {

    private val app: TrackMasterApplication
        get() = application as TrackMasterApplication

    // ============================================================
    // 当前状态
    // ============================================================

    private val _currentFenceName = MutableStateFlow<String?>(null)
    val currentFenceName: StateFlow<String?> = _currentFenceName

    private val _currentStayDuration = MutableStateFlow<Long>(0L) // 秒
    val currentStayDuration: StateFlow<Long> = _currentStayDuration

    private val _isInsideFence = MutableStateFlow(false)
    val isInsideFence: StateFlow<Boolean> = _isInsideFence

    // ============================================================
    // 今日统计
    // ============================================================

    private val _todayEnterCount = MutableStateFlow(0)
    val todayEnterCount: StateFlow<Int> = _todayEnterCount

    private val _todayExitCount = MutableStateFlow(0)
    val todayExitCount: StateFlow<Int> = _todayExitCount

    private val _todayTotalDuration = MutableStateFlow<Long>(0L) // 秒
    val todayTotalDuration: StateFlow<Long> = _todayTotalDuration

    // ============================================================
    // 本周统计
    // ============================================================

    private val _weekEnterCount = MutableStateFlow(0)
    val weekEnterCount: StateFlow<Int> = _weekEnterCount

    private val _weekExitCount = MutableStateFlow(0)
    val weekExitCount: StateFlow<Int> = _weekExitCount

    // ============================================================
    // 月度 TOP
    // ============================================================

    data class FenceDurationStats(
        val fenceName: String,
        val totalDurationSeconds: Long
    )

    private val _monthTopFences = MutableStateFlow<List<FenceDurationStats>>(emptyList())
    val monthTopFences: StateFlow<List<FenceDurationStats>> = _monthTopFences

    // ============================================================
    // 初始化
    // ============================================================

    init {
        refreshStats()
        startAutoRefresh()
    }

    /**
     * 刷新所有统计数据
     */
    fun refreshStats() {
        updateCurrentFenceStatus()
        updateTodayStats()
        updateWeekStats()
        updateMonthTopFences()
    }

    /**
     * 更新当前围栏状态
     */
    private fun updateCurrentFenceStatus() {
        val matchedFences = app.currentMatchedFences
        if (matchedFences.isNotEmpty()) {
            val fence = matchedFences.first()
            _currentFenceName.value = fence.name
            _isInsideFence.value = true

            // 计算当前停留时长
            val enterTime = app.fenceEnterTimes[fence.id]
            if (enterTime != null) {
                _currentStayDuration.value = (System.currentTimeMillis() - enterTime.time) / 1000
            }
        } else {
            _currentFenceName.value = null
            _isInsideFence.value = false
            _currentStayDuration.value = 0L
        }
    }

    /**
     * 更新今日统计
     */
    private fun updateTodayStats() {
        val today = Calendar.getInstance()
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val todayStr = dateFormat.format(today.time)

        val allRecords = app.dbHelper.getAllStayRecords()
        val todayRecords = allRecords.filter { record ->
            dateFormat.format(record.enterTime).startsWith(todayStr)
        }

        _todayEnterCount.value = todayRecords.size
        _todayExitCount.value = todayRecords.count { it.exitTime != null }
        _todayTotalDuration.value = todayRecords.sumOf { it.durationSeconds ?: 0L }
    }

    /**
     * 更新本周统计
     */
    private fun updateWeekStats() {
        val calendar = Calendar.getInstance()
        calendar.set(Calendar.DAY_OF_WEEK, calendar.firstDayOfWeek)
        val weekStart = calendar.time

        val events = app.geoFenceEvents.filter { it.time >= weekStart }
        _weekEnterCount.value = events.count { it.action == com.trackmaster.app.GeoFenceAction.ENTER }
        _weekExitCount.value = events.count { it.action == com.trackmaster.app.GeoFenceAction.EXIT }
    }

    /**
     * 更新月度 TOP 围栏
     */
    private fun updateMonthTopFences() {
        val calendar = Calendar.getInstance()
        calendar.set(Calendar.DAY_OF_MONTH, 1)
        val monthStart = calendar.time

        val allRecords = app.dbHelper.getAllStayRecords()
        val monthRecords = allRecords.filter { it.enterTime >= monthStart }

        // 按围栏分组计算总时长
        val fenceDurations = monthRecords
            .groupBy { it.fenceName }
            .mapValues { (_, records) ->
                records.sumOf { it.durationSeconds ?: 0L }
            }
            .map { FenceDurationStats(it.key, it.value) }
            .sortedByDescending { it.totalDurationSeconds }
            .take(3)

        _monthTopFences.value = fenceDurations
    }

    /**
     * 启动自动刷新（每分钟更新一次当前状态）
     */
    private fun startAutoRefresh() {
        viewModelScope.launch {
            while (isActive) {
                delay(60_000) // 1分钟
                if (_isInsideFence.value) {
                    updateCurrentFenceStatus()
                }
            }
        }
    }

    /**
     * 格式化时长为可读字符串
     */
    fun formatDuration(seconds: Long): String {
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        return when {
            hours > 0 -> "${hours}小时${minutes}分"
            minutes > 0 -> "${minutes}分钟"
            else -> "< 1分钟"
        }
    }
}
