package com.trackmaster.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trackmaster.app.DailyStayRecord
import com.trackmaster.app.data.GeoFenceAction
import com.trackmaster.app.data.repository.SessionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import com.trackmaster.app.utils.AppLog

class HomeViewModel(
    private val sessionRepo: SessionRepository
) : ViewModel() {

    private val _currentFenceName = MutableStateFlow<String?>(null)
    val currentFenceName: StateFlow<String?> = _currentFenceName

    private val _currentStayDuration = MutableStateFlow<Long>(0L)
    val currentStayDuration: StateFlow<Long> = _currentStayDuration

    private val _isInsideFence = MutableStateFlow(false)
    val isInsideFence: StateFlow<Boolean> = _isInsideFence

    private val _todayEnterCount = MutableStateFlow(0)
    val todayEnterCount: StateFlow<Int> = _todayEnterCount

    private val _todayExitCount = MutableStateFlow(0)
    val todayExitCount: StateFlow<Int> = _todayExitCount

    private val _todayTotalDuration = MutableStateFlow<Long>(0L)
    val todayTotalDuration: StateFlow<Long> = _todayTotalDuration

    private val _weekEnterCount = MutableStateFlow(0)
    val weekEnterCount: StateFlow<Int> = _weekEnterCount

    private val _weekExitCount = MutableStateFlow(0)
    val weekExitCount: StateFlow<Int> = _weekExitCount

    data class FenceDurationStats(
        val fenceName: String,
        val totalDurationSeconds: Long
    )

    private val _monthTopFences = MutableStateFlow<List<FenceDurationStats>>(emptyList())
    val monthTopFences: StateFlow<List<FenceDurationStats>> = _monthTopFences

    init {
        refreshStats()
        startAutoRefresh()

        viewModelScope.launch {
            try {
                sessionRepo.fenceStateVersion.collectLatest {
                    refreshStats()
                }
            } catch (e: Exception) {
                AppLog.e("HomeViewModel", "fenceStateVersion collect error", e)
            }
        }
    }

    fun refreshStats() {
        updateCurrentFenceStatus()
        updateTodayStats()
        updateWeekStats()
        updateMonthTopFences()
    }

    private fun updateCurrentFenceStatus() {
        val matchedFences = sessionRepo.currentMatchedFences
        if (matchedFences.isNotEmpty()) {
            val fence = matchedFences.first()
            _currentFenceName.value = fence.name
            _isInsideFence.value = true
            val enterTime = sessionRepo.fenceEnterTimes[fence.id]
            if (enterTime != null) {
                _currentStayDuration.value = (System.currentTimeMillis() - enterTime.time) / 1000
            }
        } else {
            _currentFenceName.value = null
            _isInsideFence.value = false
            _currentStayDuration.value = 0L
        }
    }

    private fun updateTodayStats() {
        val today = Calendar.getInstance()
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val todayStr = dateFormat.format(today.time)

        val todayDailyRecords = sessionRepo.getDailyStayRecordsByDate(todayStr)
        _todayTotalDuration.value = todayDailyRecords.sumOf { it.totalDurationSeconds }

        val todayEvents = sessionRepo.geoFenceEvents.filter { event ->
            dateFormat.format(event.time).startsWith(todayStr)
        }
        _todayEnterCount.value = todayEvents.count { it.action == GeoFenceAction.ENTER }
        _todayExitCount.value = todayEvents.count { it.action == GeoFenceAction.EXIT }
    }

    private fun updateWeekStats() {
        val calendar = Calendar.getInstance()
        calendar.set(Calendar.DAY_OF_WEEK, calendar.firstDayOfWeek)
        val weekStart = calendar.time

        val events = sessionRepo.geoFenceEvents.filter { it.time >= weekStart }
        _weekEnterCount.value = events.count { it.action == GeoFenceAction.ENTER }
        _weekExitCount.value = events.count { it.action == GeoFenceAction.EXIT }
    }

    private fun updateMonthTopFences() {
        viewModelScope.launch(Dispatchers.IO) {
            val calendar = Calendar.getInstance()
            val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

            val allDailyRecords = mutableListOf<DailyStayRecord>()
            calendar.set(Calendar.DAY_OF_MONTH, 1)
            val daysInMonth = calendar.getActualMaximum(Calendar.DAY_OF_MONTH)

            for (day in 1..daysInMonth) {
                calendar.set(Calendar.DAY_OF_MONTH, day)
                val dateKey = dateFormat.format(calendar.time)
                allDailyRecords.addAll(sessionRepo.getDailyStayRecordsByDate(dateKey))
            }

        val fenceDurations = allDailyRecords
            .groupBy { it.fenceName }
            .mapValues { (_, records) -> records.sumOf { it.totalDurationSeconds } }
            .map { FenceDurationStats(it.key, it.value) }
            .sortedByDescending { it.totalDurationSeconds }
            .take(3)

            _monthTopFences.value = fenceDurations
        }
    }

    private fun startAutoRefresh() {
        viewModelScope.launch {
            while (isActive) {
                delay(60_000)
                try {
                    if (_isInsideFence.value) {
                        updateCurrentFenceStatus()
                    }
                } catch (e: Exception) {
                    AppLog.e("HomeViewModel", "autoRefresh error", e)
                }
            }
        }
    }

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
