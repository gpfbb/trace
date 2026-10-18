package com.trackmaster.app.ui.statistics

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Calendar
import com.trackmaster.app.R
import com.trackmaster.app.FenceStatisticTree
import com.trackmaster.app.FenceStatistics
import com.trackmaster.app.StatisticsManager
import com.trackmaster.app.TimeDimension
import com.trackmaster.app.ui.design.currentPalette


@Composable
fun DatePickerDialog(
    initialYear: Int,
    initialMonth: Int,
    initialDay: Int,
    onDateSelected: (Int, Int, Int) -> Unit,
    onDismiss: () -> Unit
) {
    var year by remember { mutableStateOf(initialYear) }
    var month by remember { mutableStateOf(initialMonth) }
    var day by remember { mutableStateOf(initialDay) }

    val currentYear = Calendar.getInstance().get(Calendar.YEAR)
    val yearRange = 2020..(currentYear + 10)

    val calendar = Calendar.getInstance()
    calendar.set(Calendar.YEAR, year)
    calendar.set(Calendar.MONTH, month)
    val maxDay = calendar.getActualMaximum(Calendar.DAY_OF_MONTH)

    day = remember(day, maxDay) { if (day > maxDay) maxDay else day }
    
    com.trackmaster.app.ui.design.AppDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.select_date), color = currentPalette.accent) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    NumberPicker(
                        label = stringResource(R.string.year_label),
                        value = year,
                        range = yearRange,
                        onValueChange = { year = it },
                        modifier = Modifier.weight(1f)
                    )
                    NumberPicker(
                        label = stringResource(R.string.month_label),
                        value = month + 1,
                        range = 1..12,
                        onValueChange = { month = it - 1 },
                        modifier = Modifier.weight(1f)
                    )
                    NumberPicker(
                        label = stringResource(R.string.day_label),
                        value = day,
                        range = 1..maxDay,
                        onValueChange = { day = it },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        },
        confirmButton = {
            com.trackmaster.app.ui.design.DesignSecondaryButton(onClick = { onDateSelected(year, month, day) }) {
                Text(stringResource(R.string.confirm_short), color = currentPalette.accent)
            }
        },
        dismissButton = {
            com.trackmaster.app.ui.design.DesignSecondaryButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel_short), color = currentPalette.accent)
            }
        }
    )
}

@Composable
fun MonthPickerDialog(
    initialYear: Int,
    initialMonth: Int,
    onMonthSelected: (Int, Int) -> Unit,
    onDismiss: () -> Unit
) {
    var year by remember { mutableStateOf(initialYear) }
    var month by remember { mutableStateOf(initialMonth) }

    val currentYear = Calendar.getInstance().get(Calendar.YEAR)
    val yearRange = 2020..(currentYear + 10)
    
    com.trackmaster.app.ui.design.AppDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.select_month), color = currentPalette.accent) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    NumberPicker(
                        label = stringResource(R.string.year_label),
                        value = year,
                        range = yearRange,
                        onValueChange = { year = it },
                        modifier = Modifier.weight(1f)
                    )
                    NumberPicker(
                        label = stringResource(R.string.month_label),
                        value = month + 1,
                        range = 1..12,
                        onValueChange = { month = it - 1 },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        },
        confirmButton = {
            com.trackmaster.app.ui.design.DesignSecondaryButton(onClick = { onMonthSelected(year, month) }) {
                Text(stringResource(R.string.confirm_short), color = currentPalette.accent)
            }
        },
        dismissButton = {
            com.trackmaster.app.ui.design.DesignSecondaryButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel_short), color = currentPalette.accent)
            }
        }
    )
}

@Composable
fun YearPickerDialog(
    initialYear: Int,
    onYearSelected: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    var year by remember { mutableStateOf(initialYear) }

    val currentYear = Calendar.getInstance().get(Calendar.YEAR)
    val yearRange = 2020..(currentYear + 10)
    
    com.trackmaster.app.ui.design.AppDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.select_year), color = currentPalette.accent) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                NumberPicker(
                    label = stringResource(R.string.year_label),
                    value = year,
                    range = yearRange,
                    onValueChange = { year = it }
                )
            }
        },
        confirmButton = {
            com.trackmaster.app.ui.design.DesignSecondaryButton(onClick = { onYearSelected(year) }) {
                Text(stringResource(R.string.confirm_short), color = currentPalette.accent)
            }
        },
        dismissButton = {
            com.trackmaster.app.ui.design.DesignSecondaryButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel_short), color = currentPalette.accent)
            }
        }
    )
}

@Composable
fun NumberPicker(
    label: String,
    value: Int,
    range: IntRange,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = currentPalette.groupedCell
        ),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            IconButton(
                onClick = {
                    if (value < range.last) {
                        onValueChange(value + 1)
                    }
                },
                enabled = value < range.last
            ) {
                Text("▲", fontSize = 18.sp, color = currentPalette.accent)
            }
            Text(
                text = value.toString(),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = currentPalette.accent
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = currentPalette.labelSecondary
            )
            IconButton(
                onClick = {
                    if (value > range.first) {
                        onValueChange(value - 1)
                    }
                },
                enabled = value > range.first
            ) {
                Text("▼", fontSize = 18.sp, color = currentPalette.accent)
            }
        }
    }
}
