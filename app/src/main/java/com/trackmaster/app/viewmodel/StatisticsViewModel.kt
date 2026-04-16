package com.trackmaster.app.viewmodel

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trackmaster.app.TrackMasterApplication
import com.trackmaster.app.TimeDimension
import com.trackmaster.app.FenceStatistics
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Calendar

/**
 * 统计 ViewModel - 管理统计数据
 *
 * 职责：
 * - 管理日期选择
 * - 管理统计数据获取
 * - 管理统计加载状态
 */
class StatisticsViewModel(
    private val application: Application
) : ViewModel() {

    private val app: TrackMasterApplication
        get() = application as TrackMasterApplication

    private val statisticsManager
        get() = app.statisticsManager

    // ============================================================
    // 日期选择
    // ============================================================

    private val calendar = Calendar.getInstance()

    private val _selectedYear = MutableStateFlow(calendar.get(Calendar.YEAR))
    val selectedYear: StateFlow<Int> = _selectedYear

    private val _selectedMonth = MutableStateFlow(calendar.get(Calendar.MONTH))
    val selectedMonth: StateFlow<Int> = _selectedMonth

    private val _selectedDay = MutableStateFlow(calendar.get(Calendar.DAY_OF_MONTH))
    val selectedDay: StateFlow<Int> = _selectedDay

    // ============================================================
    // 统计数据
    // ============================================================

    private val _statistics = MutableStateFlow<List<FenceStatistics>>(emptyList())
    val statistics: StateFlow<List<FenceStatistics>> = _statistics

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    // ============================================================
    // 初始化
    // ============================================================

    init {
        refreshStatistics()
        startAutoRefresh()
    }

    // ============================================================
    // 操作
    // ============================================================

    /**
     * 设置日期
     */
    fun setDate(year: Int, month: Int, day: Int) {
        _selectedYear.value = year
        _selectedMonth.value = month
        _selectedDay.value = day
        refreshStatistics()
    }

    /**
     * 设置月份
     */
    fun setMonth(year: Int, month: Int) {
        _selectedYear.value = year
        _selectedMonth.value = month
        refreshStatistics()
    }

    /**
     * 设置年份
     */
    fun setYear(year: Int) {
        _selectedYear.value = year
        refreshStatistics()
    }

    /**
     * 刷新统计数据
     */
    fun refreshStatistics() {
        viewModelScope.launch {
            _isLoading.value = true

            // 获取日统计数据
            val timeRange = statisticsManager.getDateRange(
                _selectedYear.value,
                _selectedMonth.value,
                _selectedDay.value
            )

            val rawStats = statisticsManager.getStatistics(TimeDimension.DAY, timeRange)

            _statistics.value = rawStats

            _isLoading.value = false
        }
    }

    /**
     * 启动自动刷新（仅当日视图且为今天时）
     */
    private fun startAutoRefresh() {
        viewModelScope.launch {
            while (isActive) {
                delay(1000)
                if (isToday()) {
                    refreshStatistics()
                }
            }
        }
    }

    /**
     * 判断当前选择日期是否为今天
     */
    private fun isToday(): Boolean {
        val today = Calendar.getInstance()
        return _selectedYear.value == today.get(Calendar.YEAR) &&
                _selectedMonth.value == today.get(Calendar.MONTH) &&
                _selectedDay.value == today.get(Calendar.DAY_OF_MONTH)
    }

    /**
     * 返回 raw 秒数，格式化由 UI 层通过 stringResource 完成
     */
    fun formatDuration(seconds: Long): Long = seconds
}
