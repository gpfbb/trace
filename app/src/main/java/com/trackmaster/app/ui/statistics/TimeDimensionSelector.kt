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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trackmaster.app.R
import com.trackmaster.app.FenceStatisticTree
import com.trackmaster.app.FenceStatistics
import com.trackmaster.app.StatisticsManager
import com.trackmaster.app.TimeDimension
import com.trackmaster.app.ui.design.currentPalette


@Composable
fun TimeDimensionSelector(
    selectedDimension: TimeDimension,
    onDimensionSelected: (TimeDimension) -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .background(
                color = currentPalette.groupedCell,
                shape = RoundedCornerShape(24.dp)
            )
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(1f / 3f)
                .align(
                    when (selectedDimension) {
                        TimeDimension.DAY -> Alignment.CenterStart
                        TimeDimension.MONTH -> Alignment.Center
                        TimeDimension.YEAR -> Alignment.CenterEnd
                    }
                )
                .padding(2.dp)
                .background(
                    brush = Brush.linearGradient(
                        colors = listOf(currentPalette.accent, currentPalette.warning),
                        start = androidx.compose.ui.geometry.Offset(0f, 0f),
                        end = androidx.compose.ui.geometry.Offset(100f, 100f)
                    ),
                    shape = RoundedCornerShape(22.dp)
                )
        )
        
        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TimeDimension.values().forEach { dimension ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable {
                            onDimensionSelected(dimension)
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = when (dimension) {
                            TimeDimension.DAY -> stringResource(R.string.day_dimension)
                            TimeDimension.MONTH -> stringResource(R.string.month_dimension)
                            TimeDimension.YEAR -> stringResource(R.string.year_dimension)
                        },
                        // 选中的胶囊底色就是强调色，文字必须用 onAccent
                        // （浅色模式下是白字）。用 accent 会变成深橙压深橙。
                        color = if (selectedDimension == dimension) {
                            currentPalette.onAccent
                        } else {
                            currentPalette.labelSecondary
                        },
                        fontWeight = if (selectedDimension == dimension) 
                            FontWeight.Bold 
                            else 
                            FontWeight.Normal
                    )
                }
            }
        }
    }
}
