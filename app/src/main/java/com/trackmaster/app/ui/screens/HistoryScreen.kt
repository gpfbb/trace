package com.trackmaster.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.EventNote
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trackmaster.app.data.GeoFenceAction
import com.trackmaster.app.data.GeoFenceEvent
import com.trackmaster.app.TrackMasterApplication
import com.trackmaster.app.ui.theme.GlassTopCard
import com.trackmaster.app.ui.theme.GlassCard
import com.trackmaster.app.ui.theme.Success
import com.trackmaster.app.ui.theme.Danger
import com.trackmaster.app.ui.theme.primaryColor
import com.trackmaster.app.ui.theme.surfaceColor
import com.trackmaster.app.ui.theme.backgroundColor
import com.trackmaster.app.ui.theme.glassPanel
import androidx.compose.ui.res.stringResource
import com.trackmaster.app.R
import org.koin.compose.koinInject
import java.text.SimpleDateFormat
import java.util.*

/**
 * History Screen
 *
 * Displays chronological timeline view of fence events.
 */
@Composable
fun HistoryScreen(
    @Suppress("UNUSED_PARAMETER") onNavigateBack: () -> Unit
) {
    val application = koinInject<TrackMasterApplication>()
    val events = remember { mutableStateOf<List<GeoFenceEvent>>(emptyList()) }
    var selectedFilter by remember { mutableStateOf<EventFilter>(EventFilter.ALL) }

    LaunchedEffect(Unit) {
        events.value = application.geoFenceEvents
            .sortedByDescending { it.time }
    }

    val filteredEvents = when (selectedFilter) {
        EventFilter.ALL -> events.value
        EventFilter.ENTER -> events.value.filter { it.action == GeoFenceAction.ENTER }
        EventFilter.EXIT -> events.value.filter { it.action == GeoFenceAction.EXIT }
    }

    // Group by date
    val groupedEvents = filteredEvents.groupBy { event ->
        SimpleDateFormat(stringResource(R.string.date_format_day), Locale.getDefault()).format(event.time)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(surfaceColor(), backgroundColor())
                )
            )
    ) {
        // ============================================================
        // Top Bar
        // ============================================================
        GlassTopCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.History,
                        contentDescription = null,
                        tint = primaryColor()
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.history_title),
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = primaryColor()
                    )
                }
                Text(
                    text = stringResource(R.string.history_records_count, filteredEvents.size),
                    fontSize = 14.sp,
                    color = Color.Gray
                )
            }
        }

        // ============================================================
        // Filters
        // ============================================================
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                label = stringResource(R.string.filter_all),
                isSelected = selectedFilter == EventFilter.ALL,
                onClick = { selectedFilter = EventFilter.ALL }
            )
            FilterChip(
                label = stringResource(R.string.filter_enter),
                isSelected = selectedFilter == EventFilter.ENTER,
                onClick = { selectedFilter = EventFilter.ENTER }
            )
            FilterChip(
                label = stringResource(R.string.filter_exit),
                isSelected = selectedFilter == EventFilter.EXIT,
                onClick = { selectedFilter = EventFilter.EXIT }
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // ============================================================
        // Event List
        // ============================================================
        if (filteredEvents.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.EventNote,
                        contentDescription = null,
                        tint = Color.Gray,
                        modifier = Modifier.size(64.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = stringResource(R.string.no_history_records),
                        fontSize = 16.sp,
                        color = Color.Gray
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                groupedEvents.forEach { (date, dateEvents) ->
                    item {
                        Text(
                            text = date,
                            fontSize = 14.sp,
                            color = primaryColor(),
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }

                    items(dateEvents) { event ->
                        EventItem(event = event)
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(80.dp))
                }
            }
        }
    }
}

@Composable
private fun EventItem(event: GeoFenceEvent) {
    val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    val isEnter = event.action == GeoFenceAction.ENTER

    GlassCard(
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Status Icon
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(20))
                    .background(if (isEnter) Success.copy(alpha = 0.2f) else Danger.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isEnter) Icons.AutoMirrored.Filled.Login else Icons.AutoMirrored.Filled.Logout,
                    contentDescription = null,
                    tint = if (isEnter) Success else Danger,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Event Info
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (isEnter) stringResource(R.string.enter_event) else stringResource(R.string.exit_event),
                    fontSize = 12.sp,
                    color = if (isEnter) Success else Danger
                )
                Text(
                    text = event.fenceName,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color.White
                )
                Text(
                    text = "${event.latitude}, ${event.longitude}",
                    fontSize = 11.sp,
                    color = Color.Gray
                )
            }

            // Time
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = timeFormat.format(event.time),
                    fontSize = 14.sp,
                    color = Color.White
                )
                Text(
                    text = stringResource(R.string.accuracy_label, event.accuracy.toInt()),
                    fontSize = 11.sp,
                    color = Color.Gray
                )
            }
        }
    }
}

@Composable
private fun FilterChip(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier.clickable { onClick() },
        shape = RoundedCornerShape(16.dp),
        color = if (isSelected) primaryColor().copy(alpha = 0.2f) else glassPanel()
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            fontSize = 14.sp,
            color = if (isSelected) primaryColor() else Color.Gray
        )
    }
}

private enum class EventFilter {
    ALL, ENTER, EXIT
}
