package com.trackmaster.app.viewmodel

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trackmaster.app.DailyStayRecord
import com.trackmaster.app.data.FenceStayRecord
import com.trackmaster.app.data.GeoFenceAction
import com.trackmaster.app.TrackMasterApplication
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import com.trackmaster.app.utils.AppLog

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

        // 监听围栏状态版本变化，立即刷新 UI
        viewModelScope.launch {
            try {
                app.fenceStateVersion.collectLatest {
                    refreshStats()
                }
            } catch (e: Exception) {
                AppLog.e("HomeViewModel", "fenceStateVersion collect error", e)
            }
        }
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
     *
     * 【修复】使用 daily_stays 表获取已完成的分天时长数据，
     * 而非 stays 表（会导致跨天停留的全部时长错误归入进入日）。
     */
    private fun updateTodayStats() {
        val today = Calendar.getInstance()
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val todayStr = dateFormat.format(today.time)

        // 使用 daily_stays 表获取今日时长（正确分天的数据）
        val todayDailyRecords = app.dbHelper.getDailyStayRecordsByDate(todayStr)
        _todayTotalDuration.value = todayDailyRecords.sumOf { it.totalDurationSeconds }

        // 进入/离开次数从 events 表统计
        val todayEvents = app.geoFenceEvents.filter { event ->
            dateFormat.format(event.time).startsWith(todayStr)
        }
        _todayEnterCount.value = todayEvents.count { it.action == GeoFenceAction.ENTER }
        _todayExitCount.value = todayEvents.count { it.action == GeoFenceAction.EXIT }
    }

    /**
     * 更新本周统计
     */
    private fun updateWeekStats() {
        val calendar = Calendar.getInstance()
        calendar.set(Calendar.DAY_OF_WEEK, calendar.firstDayOfWeek)
        val weekStart = calendar.time

        val events = app.geoFenceEvents.filter { it.time >= weekStart }
        _weekEnterCount.value = events.count { it.action == GeoFenceAction.ENTER }
        _weekExitCount.value = events.count { it.action == GeoFenceAction.EXIT }
    }

    /**
     * 更新月度 TOP 围栏
     *
     * 【修复】使用 daily_stays 表获取已完成的分天时长数据，
     * 而非 stays 表（会导致跨天停留的全部时长错误归入进入日）。
     */
    private fun updateMonthTopFences() {
        val calendar = Calendar.getInstance()
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

        // 收集当月所有日期的 daily_stays
        val allDailyRecords = mutableListOf<DailyStayRecord>()
        calendar.set(Calendar.DAY_OF_MONTH, 1)
        val daysInMonth = calendar.getActualMaximum(Calendar.DAY_OF_MONTH)

        for (day in 1..daysInMonth) {
            calendar.set(Calendar.DAY_OF_MONTH, day)
            val dateKey = dateFormat.format(calendar.time)
            allDailyRecords.addAll(app.dbHelper.getDailyStayRecordsByDate(dateKey))
        }

        // 按围栏分组计算总时长
        val fenceDurations = allDailyRecords
            .groupBy { it.fenceName }
            .mapValues { (_, records) ->
                records.sumOf { it.totalDurationSeconds }
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
            try {
                while (isActive) {
                    delay(60_000) // 1分钟
                    if (_isInsideFence.value) {
                        updateCurrentFenceStatus()
                    }
                }
            } catch (e: Exception) {
                AppLog.e("HomeViewModel", "autoRefresh error", e)
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
