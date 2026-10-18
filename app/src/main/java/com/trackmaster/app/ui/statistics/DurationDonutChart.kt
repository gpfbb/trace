package com.trackmaster.app.ui.statistics

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.trackmaster.app.R
import com.trackmaster.app.ui.design.AppType
import com.trackmaster.app.ui.design.Radius
import com.trackmaster.app.ui.design.Space
import com.trackmaster.app.ui.design.currentPalette

/**
 * 环形图：各围栏停留时长占比。
 *
 * ## 为什么自己画而不是引入图表库
 *
 * 这里只需要一种图：看"时间都花在哪些围栏上"的占比分布。
 * Vico / MPAndroidChart 这类库要加几百 KB 依赖，还得为它们做主题适配
 * （库自带配色、文字样式、动画），收益不抵成本。
 *
 * Canvas 画环大约一百行，颜色能直接用围栏自己的颜色，
 * 深浅色模式自动跟随调色板，也不需要额外的动画（动画在这里没有信息量）。
 *
 * ## 数据从哪来
 *
 * 输入是已经算好的围栏时长列表，本组件只做**几何换算**：
 * 每段占比 → 弧度。换算逻辑在 [buildDonutSegments] 里，纯函数可单测。
 */

/** 环上的一段。 */
data class DonutSegment(
    val fenceId: String,
    val label: String,
    val color: Color,
    val durationSeconds: Long,
    /** 起始角度（度，0 在 12 点方向，顺时针） */
    val startAngle: Float,
    /** 扫过角度（度） */
    val sweepAngle: Float,
    /** 占比，0..1 */
    val fraction: Float
)

/**
 * 把围栏时长换算成环形图的扇段。
 *
 * 规则：
 * - 按时长降序
 * - 只保留前 [maxSegments] 个，其余合并成"其他"（避免几十个围栏时环上全是碎片）
 * - 时长为 0 的围栏不显示
 *
 * @param entries 围栏 id / 名称 / 时长 / 颜色
 * @param maxSegments 环上最多画几段
 */
fun buildDonutSegments(
    entries: List<DonutEntry>,
    maxSegments: Int = 6
): List<DonutSegment> {
    val positive = entries.filter { it.durationSeconds > 0 }
        .sortedByDescending { it.durationSeconds }
    if (positive.isEmpty()) return emptyList()

    val total = positive.sumOf { it.durationSeconds }
    if (total <= 0) return emptyList()

    // 超出上限的合并成一段"其他"，用中性灰。
    // 取 maxSegments-1 是因为"其他"本身也要占一段。
    val visible = if (positive.size > maxSegments) {
        val head = positive.take(maxSegments - 1)
        val restDuration = positive.drop(maxSegments - 1).sumOf { it.durationSeconds }
        head + DonutEntry(
            fenceId = OTHER_SEGMENT_ID,
            label = null,
            color = null,
            durationSeconds = restDuration
        )
    } else {
        positive
    }

    var angle = 0f
    return visible.map { entry ->
        val fraction = entry.durationSeconds.toFloat() / total
        val sweep = fraction * 360f
        val segment = DonutSegment(
            fenceId = entry.fenceId,
            label = entry.label ?: "",
            color = entry.color ?: Color.Gray,
            durationSeconds = entry.durationSeconds,
            startAngle = angle,
            sweepAngle = sweep,
            fraction = fraction
        )
        angle += sweep
        segment
    }
}

/** 环形图的输入项。颜色为 null 时用中性灰（用于"其他"）。 */
data class DonutEntry(
    val fenceId: String,
    val label: String?,
    val color: Color?,
    val durationSeconds: Long
)

const val OTHER_SEGMENT_ID = "__other__"

/**
 * 画环形图 + 图例。
 *
 * @param segments 由 [buildDonutSegments] 算好的扇段
 * @param totalDurationSeconds 总时长，显示在环心
 */
@Composable
fun DurationDonutChart(
    segments: List<DonutSegment>,
    totalDurationSeconds: Long,
    modifier: Modifier = Modifier
) {
    val palette = currentPalette
    if (segments.isEmpty()) return

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.md))
            .background(palette.groupedCell)
            .padding(Space.lg),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier.size(180.dp),
            contentAlignment = Alignment.Center
        ) {
            Canvas(Modifier.size(180.dp)) {
                val stroke = 26.dp.toPx()
                val inset = stroke / 2f
                val arcSize = Size(size.width - stroke, size.height - stroke)
                val topLeft = Offset(inset, inset)

                // 先用极淡的底色画一整圈，让"没有数据"的部分也有个视觉边界
                drawArc(
                    color = palette.separator.copy(alpha = 0.35f),
                    startAngle = 0f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = stroke)
                )

                // Compose 的 drawArc 起始角 0 在 3 点方向，顺时针。
                // 这里减 90 让 0 度落在 12 点方向，符合直觉。
                for (segment in segments) {
                    drawArc(
                        color = segment.color,
                        startAngle = segment.startAngle - 90f,
                        sweepAngle = segment.sweepAngle,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = Stroke(width = stroke)
                    )
                }
            }

            // 环心：总时长
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = formatTotalDuration(totalDurationSeconds),
                    style = AppType.title2,
                    color = palette.label
                )
                Text(
                    text = stringResource(R.string.total_label),
                    style = AppType.caption,
                    color = palette.labelSecondary
                )
            }
        }

        Spacer(Modifier.height(Space.lg))

        Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
            segments.forEach { segment ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(segment.color)
                    )
                    Spacer(Modifier.width(Space.sm))
                    Text(
                        text = segment.label.ifEmpty {
                            stringResource(R.string.other_fences)
                        },
                        style = AppType.footnote,
                        color = palette.label,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(Space.sm))
                    Text(
                        text = "${(segment.fraction * 100).toInt()}%",
                        style = AppType.footnote,
                        fontWeight = FontWeight.Medium,
                        color = palette.labelSecondary
                    )
                }
            }
        }
    }
}

/**
 * 环心的总时长。
 *
 * 必须和下方明细里的 "Total: ..." 用同一套精度，否则同一个数字
 * 在环心显示 24 min、在明细显示 24m 52s，看起来像两个值。
 */
@Composable
private fun formatTotalDuration(seconds: Long): String {
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val secs = seconds % 60
    return when {
        hours > 0 -> stringResource(R.string.duration_hours_minutes_seconds, hours, minutes, secs)
        minutes > 0 -> stringResource(R.string.duration_minutes_seconds, minutes, secs)
        else -> stringResource(R.string.duration_less_than_minute)
    }
}
