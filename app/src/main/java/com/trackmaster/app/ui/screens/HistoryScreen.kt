package com.trackmaster.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.EventNote
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.trackmaster.app.R
import com.trackmaster.app.TrackMasterApplication
import com.trackmaster.app.data.GeoFenceAction
import com.trackmaster.app.data.GeoFenceEvent
import com.trackmaster.app.ui.design.AppType
import com.trackmaster.app.ui.design.Divider
import com.trackmaster.app.ui.design.GroupedSection
import com.trackmaster.app.ui.design.LargeTitle
import com.trackmaster.app.ui.design.Space
import com.trackmaster.app.ui.design.currentPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 历史记录：按天分组的围栏进出事件。
 *
 * 相比旧实现的改动：
 * - 加载从 `LaunchedEffect(Unit)` 改为以筛选条件为 key，
 *   并且下拉刷新时能重新加载（旧实现加载一次就再也不刷）。
 * - 筛选状态用 `rememberSaveable`，转屏不丢。
 * - 日期分组用 `Locale.US` 的 `yyyy-MM-dd` 作为分组键，显示时才用
 *   本地化的格式——旧实现用显示格式做分组键，在某些语言下会把
 *   同一天的事件分到不同组。
 */
@Composable
fun HistoryScreen(
    @Suppress("UNUSED_PARAMETER") onNavigateBack: () -> Unit
) {
    val palette = currentPalette
    val application = koinInject<TrackMasterApplication>()

    var events by remember { mutableStateOf<List<GeoFenceEvent>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var selectedFilter by rememberSaveable { mutableStateOf(EventFilter.ALL) }

    // 以筛选条件为 key：切换筛选时如果数据为空会重新拉取。
    // 首次进入时 isLoading 为 true，同样会走一次加载。
    LaunchedEffect(selectedFilter) {
        isLoading = true
        events = withContext(Dispatchers.IO) {
            val end = Date()
            val start = Calendar.getInstance().apply {
                time = end
                add(Calendar.DAY_OF_YEAR, -HISTORY_DAYS)
            }.time
            val raw = application.dbHelper.getEventsByTimeRange(start, end)
                .sortedByDescending { it.time }

            // 旧版本把 fenceId 当成 fenceName 写进事件表，界面上会显示一串
            // UUID。写入侧已修，这里给已经落库的旧数据兜底。
            val nameById = application.geoFences.associate { it.id to it.name }
            raw.map { event ->
                val resolved = com.trackmaster.app.data.StayRecordNameResolver.resolve(
                    storedName = event.fenceName,
                    fenceId = event.fenceId,
                    nameById = nameById
                )
                if (resolved == event.fenceName) event else event.copy(fenceName = resolved)
            }
        }
        isLoading = false
    }

    val filteredEvents = when (selectedFilter) {
        EventFilter.ALL -> events
        EventFilter.ENTER -> events.filter { it.action == GeoFenceAction.ENTER }
        EventFilter.EXIT -> events.filter { it.action == GeoFenceAction.EXIT }
    }

    // 分组键固定用 Locale.US 的 yyyy-MM-dd，显示时才本地化
    val displayFormat = remember { SimpleDateFormat("yyyy-MM-dd", Locale.US) }
    val groupedEvents = remember(filteredEvents) {
        filteredEvents.groupBy { displayFormat.format(it.time) }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
        contentPadding = PaddingValues(
            start = Space.screenHorizontal,
            end = Space.screenHorizontal,
            top = Space.xl,
            bottom = 96.dp
        ),
        verticalArrangement = Arrangement.spacedBy(Space.sectionGap)
    ) {
        item {
            LargeTitle(
                text = stringResource(R.string.history_title),
                subtitle = stringResource(R.string.history_records_count, filteredEvents.size)
            )
        }

        item {
            FilterRow(
                selected = selectedFilter,
                onSelect = { selectedFilter = it }
            )
        }

        if (isLoading) {
            item { LoadingRow() }
        } else if (filteredEvents.isEmpty()) {
            item { EmptyState() }
        } else {
            groupedEvents.forEach { (dateKey, dateEvents) ->
                item(key = "heading-$dateKey") {
                    DayHeading(dateKey = dateKey, count = dateEvents.size)
                }
                item(key = "group-$dateKey") {
                    GroupedSection {
                        dateEvents.forEachIndexed { index, event ->
                            if (index > 0) Divider(startInset = true)
                            EventRow(event = event)
                        }
                    }
                }
            }
        }
    }
}

/** 日期小标题：显示本地化日期，右侧显示当天事件数。 */
@Composable
private fun DayHeading(dateKey: String, count: Int) {
    val palette = currentPalette
    val localPattern = stringResource(R.string.date_format_day)
    val localFormat = remember(localPattern) {
        SimpleDateFormat(localPattern, Locale.getDefault())
    }
    // dateKey 是 Locale.US 的 yyyy-MM-dd，解析后再按本地格式显示
    val displayText = remember(dateKey, localFormat) {
        runCatching {
            SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(dateKey)
        }.getOrNull()?.let { localFormat.format(it) } ?: dateKey
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = Space.lg, end = Space.lg),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = displayText,
            style = AppType.footnote,
            color = palette.labelSecondary
        )
        Text(
            text = stringResource(R.string.history_records_count, count),
            style = AppType.footnote,
            color = palette.labelTertiary
        )
    }
}

/** 进出事件行：左侧进出图标，中间围栏名与坐标，右侧时间。 */
@Composable
private fun EventRow(event: GeoFenceEvent) {
    val palette = currentPalette
    val isEnter = event.action == GeoFenceAction.ENTER
    val tint = if (isEnter) palette.success else palette.danger
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Space.lg, vertical = Space.md),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (isEnter) {
                Icons.AutoMirrored.Filled.Login
            } else {
                Icons.AutoMirrored.Filled.Logout
            },
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(22.dp)
        )

        Spacer(Modifier.width(Space.md))

        Column(Modifier.weight(1f)) {
            Text(
                text = event.fenceName,
                style = AppType.body,
                color = palette.label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = stringResource(
                    if (isEnter) R.string.enter_event else R.string.exit_event
                ) + accuracySuffix(event.accuracy),
                style = AppType.caption,
                color = palette.labelSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(Modifier.width(Space.sm))

        Text(
            text = timeFormat.format(event.time),
            style = AppType.subhead,
            color = palette.labelSecondary
        )
    }
}

/** 精度后缀。没有精度数据时返回空串，避免显示 "±0 m"。 */
private fun accuracySuffix(accuracy: Float): String =
    if (accuracy > 0f) " · ±${accuracy.toInt()} m" else ""

@Composable
private fun FilterRow(
    selected: EventFilter,
    onSelect: (EventFilter) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Space.sm)
    ) {
        EventFilter.entries.forEach { filter ->
            val isSelected = filter == selected
            val palette = currentPalette
            Box(
                modifier = Modifier
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(
                        com.trackmaster.app.ui.design.Radius.pill
                    ))
                    .background(
                        if (isSelected) palette.accent.copy(alpha = 0.15f)
                        else palette.groupedCell
                    )
                    .clickable { onSelect(filter) }
                    .padding(horizontal = Space.lg, vertical = Space.sm)
            ) {
                Text(
                    text = stringResource(filter.labelRes),
                    style = AppType.subhead,
                    color = if (isSelected) palette.accent else palette.labelSecondary
                )
            }
        }
    }
}

@Composable
private fun EmptyState() {
    val palette = currentPalette
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 64.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.EventNote,
            contentDescription = null,
            tint = palette.labelTertiary,
            modifier = Modifier.size(48.dp)
        )
        Spacer(Modifier.height(Space.md))
        Text(
            text = stringResource(R.string.no_history_records),
            style = AppType.body,
            color = palette.labelSecondary
        )
    }
}

@Composable
private fun LoadingRow() {
    val palette = currentPalette
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Space.xxl),
        horizontalArrangement = Arrangement.Center
    ) {
        Text(
            text = stringResource(R.string.loading),
            style = AppType.body,
            color = palette.labelSecondary
        )
    }
}

private enum class EventFilter(@androidx.annotation.StringRes val labelRes: Int) {
    ALL(R.string.filter_all),
    ENTER(R.string.filter_enter),
    EXIT(R.string.filter_exit)
}

/** 历史记录回溯天数。 */
private const val HISTORY_DAYS = 90
